package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.roxycode.core.Sandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for analyzing and manipulating Java source code using JavaParser.
 */
@ScriptService("javaService")
@Singleton
public class JavaService {

    @Inject
    Sandbox sandbox;

    @Inject
    com.github.javaparser.JavaParser javaParser;

    /**
     * Analyzes a Java file and returns a summary of its structure.
     *
     * @param pathStr The path to the Java file.
     * @return A summary of the file's classes, methods, and imports.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Analyzes a Java file and returns a summary of its classes, methods, and imports")
    public JavaFileSummary analyzeFile(String pathStr) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
        List<String> imports = cu.getImports().stream().map(ImportDeclaration::getNameAsString).collect(Collectors.toList());
        List<ClassSummary> classes = cu.findAll(TypeDeclaration.class).stream().map(this::summarizeType).collect(Collectors.toList());
        return new JavaFileSummary(classes, imports);
    }

    private ClassSummary summarizeType(TypeDeclaration<?> n) {
        List<MethodSummary> methods = new ArrayList<>();
        List<FieldSummary> fields = new ArrayList<>();
        for (BodyDeclaration<?> member : n.getMembers()) {
            if (member instanceof MethodDeclaration m) {
                methods.add(summarizeMethod(m));
            } else if (member instanceof FieldDeclaration f) {
                f.getVariables().forEach(v -> {
                    fields.add(new FieldSummary(v.getNameAsString(), v.getTypeAsString(), f.getComment().map(Node::toString).orElse(""), v.getBegin().map(p -> p.line).orElse(-1), v.getEnd().map(p -> p.line).orElse(-1)));
                });
            }
        }
        List<String> dependencies = extractDependencies(n);
        boolean isInterface = n instanceof ClassOrInterfaceDeclaration ci && ci.isInterface();
        return new ClassSummary(n.getNameAsString(), n.getComment().map(Node::toString).orElse(""), methods, fields, isInterface, dependencies);
    }

    private MethodSummary summarizeMethod(MethodDeclaration m) {
        return new MethodSummary(m.getNameAsString(), m.getDeclarationAsString(), m.getComment().map(Node::toString).orElse(""), m.getBegin().map(p -> p.line).orElse(-1), m.getEnd().map(p -> p.line).orElse(-1));
    }

    /**
     * Retrieves the dependencies of a specific class within a Java file.
     *
     * @param pathStr   The path to the Java file.
     * @param className The name of the class.
     * @return A list of class names that the specified class depends on.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Returns the list of classes that the specified class depends on (extends, implements, fields, method parameters, local variables)")
    public List<String> getClassDependencies(String pathStr, String className) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
        Optional<TypeDeclaration> typeDecl = cu.findAll(TypeDeclaration.class).stream().filter(c -> c.getNameAsString().equals(className)).map(t -> (TypeDeclaration) t).findFirst();
        if (typeDecl.isPresent()) {
            return extractDependencies(typeDecl.get());
        } else {
            throw new RuntimeException("Type " + className + " not found in file " + path);
        }
    }

    private List<String> extractDependencies(TypeDeclaration<?> n) {
        return n.findAll(ClassOrInterfaceType.class).stream().map(ClassOrInterfaceType::getNameAsString).filter(name -> !name.equals(n.getNameAsString())).filter(name -> !isJavaLibraryType(name)).distinct().sorted().collect(Collectors.toList());
    }

    private boolean isJavaLibraryType(String name) {
        return List.of("String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Character", "Short", "Object", "List", "Set", "Map", "Optional", "ArrayList", "HashMap", "HashSet", "Collection", "Stream", "Path", "Files", "IOException", "Exception", "RuntimeException").contains(name);
    }

    /**
     * Retrieves the source code of a specific method in a class.
     *
     * @param pathStr    The path to the Java file.
     * @param className  The name of the class.
     * @param methodName The name of the method.
     * @return An Optional containing the method's source code, or empty if not found.
     */
    @LLMDoc("Returns the source code of a specific method in a class")
    public Optional<String> getMethodSource(String pathStr, String className, String methodName) {
        Path path = sandbox.resolve(pathStr);
        try {
            CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                if (type.getNameAsString().equals(className)) {
                    for (BodyDeclaration<?> member : type.getMembers()) {
                        if (member instanceof MethodDeclaration m) {
                            if (m.getNameAsString().equals(methodName)) {
                                return Optional.of(m.toString());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return Optional.empty();
    }

    /**
     * Replaces the source code of a specific method in a class.
     *
     * @param pathStr         The path to the Java file.
     * @param className       The name of the class.
     * @param methodName      The name of the method.
     * @param newMethodSource The new source code for the method.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Replaces the source code of a specific method in a class")
    public void replaceMethod(String pathStr, String className, String methodName, String newMethodSource) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            if (type.getNameAsString().equals(className)) {
                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof MethodDeclaration m) {
                        if (m.getNameAsString().equals(methodName)) {
                            MethodDeclaration newMethod = javaParser.parseMethodDeclaration(newMethodSource).getResult().orElseThrow(() -> new RuntimeException("Failed to parse method: " + newMethodSource));
                            m.replace(newMethod);
                            Files.writeString(path, cu.toString());
                            return;
                        }
                    }
                }
                throw new RuntimeException("Method " + methodName + " not found in type " + className);
            }
        }
        throw new RuntimeException("Type " + className + " not found in file " + path);
    }

    /**
     * Retrieves the source code of a specific field in a class.
     *
     * @param pathStr   The path to the Java file.
     * @param className The name of the class.
     * @param fieldName The name of the field.
     * @return An Optional containing the field's source code, or empty if not found.
     */
    @LLMDoc("Returns the source code of a specific field in a class")
    public Optional<String> getFieldSource(String pathStr, String className, String fieldName) {
        Path path = sandbox.resolve(pathStr);
        try {
            CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                if (type.getNameAsString().equals(className)) {
                    for (BodyDeclaration<?> member : type.getMembers()) {
                        if (member instanceof FieldDeclaration f) {
                            if (f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName))) {
                                return Optional.of(f.toString());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return Optional.empty();
    }

    /**
     * Replaces the source code of a specific field in a class.
     *
     * @param pathStr        The path to the Java file.
     * @param className      The name of the class.
     * @param fieldName      The name of the field.
     * @param newFieldSource The new source code for the field.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Replaces the source code of a specific field in a class")
    public void replaceField(String pathStr, String className, String fieldName, String newFieldSource) throws IOException {
        Path path = sandbox.resolve(pathStr);
        CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            if (type.getNameAsString().equals(className)) {
                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof FieldDeclaration f) {
                        if (f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName))) {
                            BodyDeclaration<?> newField = javaParser.parseBodyDeclaration(newFieldSource).getResult().orElseThrow(() -> new RuntimeException("Failed to parse field: " + newFieldSource));
                            f.replace(newField);
                            Files.writeString(path, cu.toString());
                            return;
                        }
                    }
                }
                throw new RuntimeException("Field " + fieldName + " not found in type " + className);
            }
        }
        throw new RuntimeException("Type " + className + " not found in file " + path);
    }

    /**
     * Retrieves the javadoc of a specific method in a class.
     *
     * @param pathStr    The path to the Java file.
     * @param className  The name of the class.
     * @param methodName The name of the method.
     * @return An Optional containing the method's javadoc, or empty if not found.
     */
    @LLMDoc("Returns the javadoc of a specific method in a class")
    public Optional<String> getMethodJavadoc(String pathStr, String className, String methodName) {
        Path path = sandbox.resolve(pathStr);
        try {
            CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                if (type.getNameAsString().equals(className)) {
                    for (BodyDeclaration<?> member : type.getMembers()) {
                        if (member instanceof MethodDeclaration m) {
                            if (m.getNameAsString().equals(methodName)) {
                                return m.getComment().map(Node::toString);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return Optional.empty();
    }

    /**
     * Retrieves the javadoc of a specific class.
     *
     * @param pathStr   The path to the Java file.
     * @param className The name of the class.
     * @return An Optional containing the class's javadoc, or empty if not found.
     */
    @LLMDoc("Returns the javadoc of a specific class")
    public Optional<String> getClassJavadoc(String pathStr, String className) {
        Path path = sandbox.resolve(pathStr);
        try {
            CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
            return cu.findAll(TypeDeclaration.class).stream().filter(c -> c.getNameAsString().equals(className)).findFirst().flatMap(c -> c.getComment().map(Node::toString));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Retrieves the javadoc of a specific field in a class.
     *
     * @param pathStr   The path to the Java file.
     * @param className The name of the class.
     * @param fieldName The name of the field.
     * @return An Optional containing the field's javadoc, or empty if not found.
     */
    @LLMDoc("Returns the javadoc of a specific field in a class")
    public Optional<String> getFieldJavadoc(String pathStr, String className, String fieldName) {
        Path path = sandbox.resolve(pathStr);
        try {
            CompilationUnit cu = javaParser.parse(path).getResult().orElseThrow(() -> new RuntimeException("Failed to parse file: " + path));
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                if (type.getNameAsString().equals(className)) {
                    for (BodyDeclaration<?> member : type.getMembers()) {
                        if (member instanceof FieldDeclaration f) {
                            if (f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName))) {
                                return f.getComment().map(Node::toString);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return Optional.empty();
    }

    public record JavaFileSummary(List<ClassSummary> classes, List<String> imports) {
    }

    public record ClassSummary(String name, String javadoc, List<MethodSummary> methods, List<FieldSummary> fields, boolean isInterface, List<String> dependencies) {
    }

    public record MethodSummary(String name, String signature, String javadoc, int beginLine, int endLine) {
    }

    public record FieldSummary(String name, String type, String javadoc, int beginLine, int endLine) {
    }
}
