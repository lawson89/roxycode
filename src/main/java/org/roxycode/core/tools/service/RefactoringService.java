package org.roxycode.core.tools.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.VoidType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for performing advanced refactorings on Java source code.
 */
@ScriptService("refactoringService")
@Singleton
public class RefactoringService {

    private static final Logger logger = LoggerFactory.getLogger(RefactoringService.class);

    @Inject
    Sandbox sandbox;

    @LLMDoc("Organizes imports in a Java file by removing unused ones.")
    public void organizeImports(String pathStr) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = StaticJavaParser.parse(path);

        Set<String> usedNames = new HashSet<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(type -> usedNames.add(type.getNameAsString()));
        cu.findAll(NameExpr.class).forEach(name -> usedNames.add(name.getNameAsString()));

        List<ImportDeclaration> imports = cu.getImports();
        List<ImportDeclaration> toRemove = imports.stream()
                .filter(id -> !id.isAsterisk() && !id.isStatic())
                .filter(id -> {
                    String name = id.getName().getIdentifier();
                    return !usedNames.contains(name);
                })
                .collect(Collectors.toList());

        if (!toRemove.isEmpty()) {
            logger.info("Removing {} unused imports from {}", toRemove.size(), pathStr);
            toRemove.forEach(ImportDeclaration::remove);
            Files.writeString(path, cu.toString());
        }
    }

    @LLMDoc("Renames a class across the entire project, updating references and the file name.")
    public void renameClass(String oldFullyQualifiedName, String newName) throws IOException {
        int lastDot = oldFullyQualifiedName.lastIndexOf('.');
        String packageName = (lastDot != -1) ? oldFullyQualifiedName.substring(0, lastDot) : "";
        String oldSimpleName = (lastDot != -1) ? oldFullyQualifiedName.substring(lastDot + 1) : oldFullyQualifiedName;

        Path root = sandbox.getRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());

            for (Path javaFile : javaFiles) {
                updateClassReferencesInFile(javaFile, packageName, oldSimpleName, packageName, newName);
            }
        }
    }

    @LLMDoc("Moves a class to a new package, updating references and file location.")
    public void moveClass(String fullyQualifiedName, String newPackageName) throws IOException {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        String oldPackageName = (lastDot != -1) ? fullyQualifiedName.substring(0, lastDot) : "";
        String simpleName = (lastDot != -1) ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;

        Path root = sandbox.getRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());

            for (Path javaFile : javaFiles) {
                updateClassReferencesInFile(javaFile, oldPackageName, simpleName, newPackageName, simpleName);
            }
        }
    }

    private void updateClassReferencesInFile(Path javaFile, String oldPackage, String oldName, String newPackage, String newName) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        boolean changed = false;

        String filePackage = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        boolean wasInSamePackage = filePackage.equals(oldPackage);
        boolean isImported = cu.getImports().stream()
                .anyMatch(id -> id.getNameAsString().equals(oldPackage + "." + oldName));

        // 1. Update package declaration if this IS the class being moved
        if (wasInSamePackage && javaFile.getFileName().toString().equals(oldName + ".java")) {
            cu.setPackageDeclaration(newPackage);
            changed = true;
        }

        // 2. Update class name if it changed
        if (wasInSamePackage || isImported) {
            for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cd.getNameAsString().equals(oldName)) {
                    cd.setName(newName);
                    changed = true;
                }
            }
            for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
                if (type.getNameAsString().equals(oldName)) {
                    type.setName(newName);
                    changed = true;
                }
            }
            for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                if (creation.getType().getNameAsString().equals(oldName)) {
                    creation.getType().setName(newName);
                    changed = true;
                }
            }
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (call.getScope().isPresent() && call.getScope().get() instanceof NameExpr) {
                    NameExpr scope = (NameExpr) call.getScope().get();
                    if (scope.getNameAsString().equals(oldName)) {
                        scope.setName(newName);
                        changed = true;
                    }
                }
            }

            // 3. Update or Add/Remove Imports
            boolean needsNewImport = !filePackage.equals(newPackage);
            boolean foundOldImport = false;
            
            List<ImportDeclaration> imports = cu.getImports();
            for (int i = 0; i < imports.size(); i++) {
                ImportDeclaration id = imports.get(i);
                if (id.getNameAsString().equals(oldPackage + "." + oldName)) {
                    if (needsNewImport) {
                        id.setName(newPackage + "." + newName);
                    } else {
                        id.remove(); // No longer needs import if now in same package
                    }
                    foundOldImport = true;
                    changed = true;
                }
            }
            
            if (!foundOldImport && wasInSamePackage && !filePackage.equals(newPackage) && !javaFile.getFileName().toString().equals(oldName + ".java")) {
                 // Used to be in same package, now needs an import
                 cu.addImport(newPackage + "." + newName);
                 changed = true;
            }
        }

        if (changed) {
            Files.writeString(javaFile, cu.toString());
            
            // If we renamed or moved, we might need to move the file
            if (javaFile.getFileName().toString().equals(oldName + ".java") && wasInSamePackage) {
                // Determine new path based on package
                Path srcRoot = findSourceRoot(javaFile, oldPackage);
                Path newDirPath = srcRoot.resolve(newPackage.replace('.', '/'));
                Files.createDirectories(newDirPath);
                Path newFilePath = newDirPath.resolve(newName + ".java");
                
                if (!javaFile.equals(newFilePath)) {
                    Files.move(javaFile, newFilePath);
                    logger.info("Moved file {} to {}", javaFile, newFilePath);
                }
            }
        }
    }

    private Path findSourceRoot(Path javaFile, String packageName) {
        Path parent = javaFile.getParent();
        String[] parts = packageName.isEmpty() ? new String[0] : packageName.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            parent = parent.getParent();
        }
        return parent;
    }

    @LLMDoc("Renames a class declaration in a specific file.")
    public void renameClassInFile(String pathStr, String oldName, String newName) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = StaticJavaParser.parse(path);
        boolean changed = false;
        for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cd.getNameAsString().equals(oldName)) {
                cd.setName(newName);
                changed = true;
            }
        }
        if (changed) Files.writeString(path, cu.toString());
    }

    @LLMDoc("Extracts a range of lines into a new private method in the same class.")
    public void extractMethod(String pathStr, int startLine, int endLine, String newMethodName) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = StaticJavaParser.parse(path);

        MethodDeclaration targetMethod = null;
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (md.getRange().isPresent() && 
                md.getRange().get().begin.line <= startLine && 
                md.getRange().get().end.line >= endLine) {
                targetMethod = md;
                break;
            }
        }

        if (targetMethod == null || !targetMethod.getBody().isPresent()) {
            throw new IllegalArgumentException("Could not find a method containing the specified line range.");
        }

        BlockStmt body = targetMethod.getBody().get();
        List<Statement> allStatements = body.getStatements();
        List<Statement> statementsToExtract = new ArrayList<>();

        for (Statement stmt : allStatements) {
            if (stmt.getRange().isPresent()) {
                int line = stmt.getRange().get().begin.line;
                if (line >= startLine && line <= endLine) {
                    statementsToExtract.add(stmt);
                }
            }
        }

        if (statementsToExtract.isEmpty()) {
            throw new IllegalArgumentException("No statements found in the specified line range.");
        }

        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.setName(newMethodName);
        newMethod.setType(new VoidType());
        newMethod.setModifiers(Modifier.Keyword.PRIVATE);
        BlockStmt newBody = new BlockStmt();
        statementsToExtract.forEach(newBody::addStatement);
        newMethod.setBody(newBody);

        ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) targetMethod.getParentNode().get();
        clazz.addMember(newMethod);

        MethodCallExpr call = new MethodCallExpr(null, newMethodName);
        Statement callStmt = StaticJavaParser.parseStatement(call.toString() + ";");
        
        BlockStmt updatedBody = new BlockStmt();
        boolean callInserted = false;
        for (Statement stmt : allStatements) {
            if (statementsToExtract.contains(stmt)) {
                if (!callInserted) {
                    updatedBody.addStatement(callStmt);
                    callInserted = true;
                }
            } else {
                updatedBody.addStatement(stmt);
            }
        }
        targetMethod.setBody(updatedBody);
        Files.writeString(path, cu.toString());
    }

    @LLMDoc("Extracts an interface from a class, including specified methods.")
    public void extractInterface(String classPathStr, String interfaceName, List<String> methodNames) throws IOException {
        Path classPath = sandbox.resolve(classPathStr);
        CompilationUnit classCu = StaticJavaParser.parse(classPath);
        
        ClassOrInterfaceDeclaration clazz = classCu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException("No class found in " + classPathStr));

        CompilationUnit interfaceCu = new CompilationUnit();
        classCu.getPackageDeclaration().ifPresent(interfaceCu::setPackageDeclaration);
        
        ClassOrInterfaceDeclaration interfece = interfaceCu.addInterface(interfaceName);
        interfece.setModifiers(Modifier.Keyword.PUBLIC);

        for (String methodName : methodNames) {
            clazz.getMethodsByName(methodName).forEach(md -> {
                MethodDeclaration interfaceMethod = interfece.addMethod(md.getNameAsString());
                interfaceMethod.setType(md.getType());
                interfaceMethod.setParameters(md.getParameters());
                interfaceMethod.setBody(null);
            });
        }

        clazz.addImplementedType(interfaceName);
        Files.writeString(classPath, classCu.toString());
        Path interfacePath = classPath.resolveSibling(interfaceName + ".java");
        Files.writeString(interfacePath, interfaceCu.toString());
        logger.info("Extracted interface {} from {}", interfaceName, classPathStr);
    }
}
