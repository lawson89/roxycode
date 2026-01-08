package org.roxycode.core.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

@Singleton
public class JavaSourceGraphService {

    private static final Logger LOG = LoggerFactory.getLogger(JavaSourceGraphService.class);

    /**
     * Scans a directory of .java files and generates a Mermaid dependency graph.
     *
     * @param rootPath Path to the 'src/main/java' folder.
     * @return Mermaid syntax string.
     */
    public String generateMermaidGraph(Path rootPath) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("classDiagram\n");

        // Use a synchronized set if you plan to use parallel streams,
        // otherwise a standard HashSet is fine for sequential streams.
        Set<String> relationships = new HashSet<>();

        if (!Files.exists(rootPath)) {
            LOG.error("Source path does not exist: {}", rootPath);
            return "classDiagram\nnote \"Path not found: " + rootPath + "\"";
        }

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> parseFile(path.toFile(), mermaid, relationships));
        } catch (IOException e) {
            LOG.error("Error walking source files", e);
            return "classDiagram\nnote \"Error reading files\"";
        }

        return mermaid.toString();
    }

    private void parseFile(File file, StringBuilder sb, Set<String> relationships) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = clazz.getNameAsString();

                // 1. Inheritance
                clazz.getExtendedTypes().forEach(superType ->
                        addRelation(sb, relationships, superType.getNameAsString() + " <|-- " + className)
                );

                // 2. Interfaces
                clazz.getImplementedTypes().forEach(interfaceType ->
                        addRelation(sb, relationships, interfaceType.getNameAsString() + " <|.. " + className)
                );

                // 3. Fields (Composition)
                for (FieldDeclaration field : clazz.getFields()) {
                    Type type = field.getCommonType();
                    if (type.isClassOrInterfaceType()) {
                        analyzeType(className, type.asClassOrInterfaceType(), sb, relationships);
                    }
                }
            });
        } catch (Exception e) {
            // Log but don't fail the whole process if one file is malformed
            LOG.warn("Could not parse file: {}", file.getName());
        }
    }

    private void analyzeType(String sourceClass, ClassOrInterfaceType type, StringBuilder sb, Set<String> relationships) {
        String targetName = type.getNameAsString();

        // Handle Generics (e.g. List<Order>)
        if (type.getTypeArguments().isPresent()) {
            type.getTypeArguments().get().forEach(genericArg -> {
                if (genericArg.isClassOrInterfaceType()) {
                    String genericName = genericArg.asClassOrInterfaceType().getNameAsString();
                    if (isCustomType(genericName)) {
                        addRelation(sb, relationships, sourceClass + " --> " + genericName);
                    }
                }
            });
        }
        // Handle standard references
        else if (isCustomType(targetName)) {
            addRelation(sb, relationships, sourceClass + " --> " + targetName);
        }
    }

    private void addRelation(StringBuilder sb, Set<String> relationships, String relation) {
        if (!relationships.contains(relation)) {
            sb.append("    ").append(relation).append("\n");
            relationships.add(relation);
        }
    }

    private boolean isCustomType(String name) {
        // Filter out basic Java types to keep the graph clean
        return !name.startsWith("String") &&
               !name.startsWith("Integer") &&
               !name.startsWith("List") &&
               !name.startsWith("Map") &&
               !name.startsWith("Set") &&
               !name.equals("int") &&
               !name.equals("boolean") &&
               !name.equals("long");
    }
}