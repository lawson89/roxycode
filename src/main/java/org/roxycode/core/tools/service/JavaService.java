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
import org.roxycode.core.Sandbox;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * Initializes the Java analysis engine, setting the language level to Java 21.
     */
    @LLMDoc("Initializes the Java analysis engine")
    @PostConstruct
    public void init() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

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
                .flatMap(f -> f.getVariables().stream().map(v -> new FieldSummary(
                        v.getNameAsString(),
                        v.getTypeAsString(),
                        f.getComment().map(Node::toString).orElse(""),
                        v.getBegin().map(p -> p.line).orElse(-1),
                        v.getEnd().map(p -> p.line).orElse(-1)
                )))
                .collect(Collectors.toList());

        List<String> dependencies = extractDependencies(n);

        return new ClassSummary(
                n.getNameAsString(),
                n.getComment().map(Node::toString).orElse(""),
                methods,
                fields,
                n.isInterface(),
                dependencies
        );
    }

    private MethodSummary summarizeMethod(MethodDeclaration m) {
        return new MethodSummary(
                m.getNameAsString(),
                m.getDeclarationAsString(),
                m.getComment().map(Node::toString).orElse(""),
                m.getBegin().map(p -> p.line).orElse(-1),
                m.getEnd().map(p -> p.line).orElse(-1)
        );
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
            CompilationUnit cu = StaticJavaParser.parse(path);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(className))
                    .flatMap(c -> c.getMethodsByName(methodName).stream())
                    .findFirst()
                    .flatMap(m -> m.getComment().map(Node::toString));
        } catch (IOException e) {
            return Optional.empty();
        }
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
            CompilationUnit cu = StaticJavaParser.parse(path);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(className))
                    .findFirst()
                    .flatMap(c -> c.getComment().map(Node::toString));
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
            CompilationUnit cu = StaticJavaParser.parse(path);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(className))
                    .flatMap(c -> c.getFields().stream())
                    .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName)))
                    .findFirst()
                    .flatMap(f -> f.getComment().map(Node::toString));
        } catch (IOException e) {
            return Optional.empty();
        }
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
