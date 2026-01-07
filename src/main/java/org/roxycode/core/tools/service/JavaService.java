package org.roxycode.core.tools.service;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ScriptService("javaService")
@Singleton
public class JavaService {

    @LLMDoc("Initializes the Java analysis engine")
    @PostConstruct
    public void init() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    @LLMDoc("Analyzes a Java file and returns a summary of its classes, methods, and imports")
    public JavaFileSummary analyzeFile(Path path) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(path);

        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .collect(Collectors.toList());

        List<ClassSummary> classes = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .map(this::summarizeClass)
                .collect(Collectors.toList());

        return new JavaFileSummary(classes, imports);
    }

    private ClassSummary summarizeClass(ClassOrInterfaceDeclaration n) {
        List<MethodSummary> methods = n.getMethods().stream()
                .map(this::summarizeMethod)
                .collect(Collectors.toList());

        List<FieldSummary> fields = n.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> new FieldSummary(
                        v.getNameAsString(),
                        v.getTypeAsString(),
                        v.getBegin().map(p -> p.line).orElse(-1),
                        v.getEnd().map(p -> p.line).orElse(-1)
                ))
                .collect(Collectors.toList());

        List<String> dependencies = extractDependencies(n);

        return new ClassSummary(n.getNameAsString(), methods, fields, n.isInterface(), dependencies);
    }

    private MethodSummary summarizeMethod(MethodDeclaration m) {
        return new MethodSummary(
                m.getNameAsString(),
                m.getDeclarationAsString(),
                m.getBegin().map(p -> p.line).orElse(-1),
                m.getEnd().map(p -> p.line).orElse(-1)
        );
    }

    @LLMDoc("Returns the list of classes that the specified class depends on (extends, implements, fields, method parameters, local variables)")
    public List<String> getClassDependencies(Path path, String className) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(path);
        Optional<ClassOrInterfaceDeclaration> classDecl = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(className))
                .findFirst();

        if (classDecl.isPresent()) {
            return extractDependencies(classDecl.get());
        } else {
            throw new RuntimeException("Class " + className + " not found in file " + path);
        }
    }

    private List<String> extractDependencies(ClassOrInterfaceDeclaration n) {
        return n.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .filter(name -> !name.equals(n.getNameAsString()))
                .filter(name -> !isJavaLibraryType(name))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private boolean isJavaLibraryType(String name) {
        return List.of("String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Character", "Short",
                "Object", "List", "Set", "Map", "Optional", "ArrayList", "HashMap", "HashSet", "Collection", "Stream",
                "Path", "Files", "IOException", "Exception", "RuntimeException").contains(name);
    }

    @LLMDoc("Returns the source code of a specific method in a class")
    public Optional<String> getMethodSource(Path path, String className, String methodName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(path);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(className))
                    .flatMap(c -> c.getMethodsByName(methodName).stream())
                    .findFirst()
                    .map(Node::toString);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @LLMDoc("Replaces the source code of a specific method in a class")
    public void replaceMethod(Path path, String className, String methodName, String newMethodSource) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(path);
        Optional<ClassOrInterfaceDeclaration> classDecl = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(className))
                .findFirst();

        if (classDecl.isPresent()) {
            List<MethodDeclaration> methods = classDecl.get().getMethodsByName(methodName);
            if (!methods.isEmpty()) {
                MethodDeclaration newMethod = StaticJavaParser.parseMethodDeclaration(newMethodSource);
                methods.get(0).replace(newMethod);
                Files.writeString(path, cu.toString());
            } else {
                throw new RuntimeException("Method " + methodName + " not found in class " + className);
            }
        } else {
            throw new RuntimeException("Class " + className + " not found in file " + path);
        }
    }

    @LLMDoc("Returns the source code of a specific field in a class")
    public Optional<String> getFieldSource(Path path, String className, String fieldName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(path);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(className))
                    .flatMap(c -> c.getFields().stream())
                    .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName)))
                    .findFirst()
                    .map(Node::toString);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @LLMDoc("Replaces the source code of a specific field in a class")
    public void replaceField(Path path, String className, String fieldName, String newFieldSource) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(path);
        Optional<ClassOrInterfaceDeclaration> classDecl = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(className))
                .findFirst();

        if (classDecl.isPresent()) {
            Optional<FieldDeclaration> fieldDecl = classDecl.get().getFields().stream()
                    .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName)))
                    .findFirst();

            if (fieldDecl.isPresent()) {
                BodyDeclaration<?> newField = StaticJavaParser.parseBodyDeclaration(newFieldSource);
                if (newField.isFieldDeclaration()) {
                    fieldDecl.get().replace(newField);
                    Files.writeString(path, cu.toString());
                } else {
                    throw new RuntimeException("New source is not a valid field declaration: " + newFieldSource);
                }
            } else {
                throw new RuntimeException("Field " + fieldName + " not found in class " + className);
            }
        } else {
            throw new RuntimeException("Class " + className + " not found in file " + path);
        }
    }

    public record JavaFileSummary(List<ClassSummary> classes, List<String> imports) {
    }

    public record ClassSummary(String name, List<MethodSummary> methods, List<FieldSummary> fields, boolean isInterface, List<String> dependencies) {
    }

    public record MethodSummary(String name, String signature, int beginLine, int endLine) {
    }

    public record FieldSummary(String name, String type, int beginLine, int endLine) {
    }
}
