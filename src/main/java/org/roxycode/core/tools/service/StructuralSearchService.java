package org.roxycode.core.tools.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for performing structural and semantic searches on Java source code.
 */
@ScriptService("structuralSearchService")
@Singleton
public class StructuralSearchService {

    @Inject
    Sandbox sandbox;

    @PostConstruct
    public void init() {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver);
    }

    @LLMDoc("Finds empty catch blocks in the specified directory")
    public List<SearchResult> findEmptyCatchBlocks(String pathStr) throws IOException {
        return walkAndSearch(pathStr, cu -> {
            List<SearchResult> results = new ArrayList<>();
            cu.findAll(CatchClause.class).forEach(cc -> {
                if (cc.getBody().getStatements().isEmpty()) {
                    results.add(new SearchResult(
                            "", 
                            "", 
                            "catch (" + cc.getParameter().toString() + ")",
                            cc.getBegin().map(p -> p.line).orElse(-1),
                            cc.toString()
                    ));
                }
            });
            return results;
        });
    }

    @LLMDoc("Finds methods or classes marked with @Deprecated that lack a Javadoc comment")
    public List<SearchResult> findDeprecatedWithoutJavadoc(String pathStr) throws IOException {
        return walkAndSearch(pathStr, cu -> {
            List<SearchResult> results = new ArrayList<>();
            cu.findAll(MethodDeclaration.class).forEach(m -> {
                if (m.isAnnotationPresent("Deprecated") && !m.getJavadoc().isPresent()) {
                    results.add(new SearchResult(
                            "", "",
                            m.getNameAsString(),
                            m.getBegin().map(p -> p.line).orElse(-1),
                            m.getDeclarationAsString()
                    ));
                }
            });
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                if (c.isAnnotationPresent("Deprecated") && !c.getJavadoc().isPresent()) {
                    results.add(new SearchResult(
                            "", "",
                            c.getNameAsString(),
                            c.getBegin().map(p -> p.line).orElse(-1),
                            "class " + c.getNameAsString()
                    ));
                }
            });
            return results;
        });
    }

    @LLMDoc("Finds methods with more than the specified number of parameters")
    public List<SearchResult> findMethodsWithTooManyParameters(String pathStr, int threshold) throws IOException {
        return walkAndSearch(pathStr, cu -> {
            List<SearchResult> results = new ArrayList<>();
            cu.findAll(MethodDeclaration.class).forEach(m -> {
                if (m.getParameters().size() > threshold) {
                    results.add(new SearchResult(
                            "", "",
                            m.getNameAsString(),
                            m.getBegin().map(p -> p.line).orElse(-1),
                            m.getDeclarationAsString()
                    ));
                }
            });
            return results;
        });
    }

    @LLMDoc("Finds classes with more than the specified number of lines")
    public List<SearchResult> findLargeClasses(String pathStr, int lineThreshold) throws IOException {
        return walkAndSearch(pathStr, cu -> {
            List<SearchResult> results = new ArrayList<>();
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                int lines = c.getEnd().map(p -> p.line).orElse(0) - c.getBegin().map(p -> p.line).orElse(0);
                if (lines > lineThreshold) {
                    results.add(new SearchResult(
                            "", "",
                            c.getNameAsString(),
                            c.getBegin().map(p -> p.line).orElse(-1),
                            "class " + c.getNameAsString() + " (" + lines + " lines)"
                    ));
                }
            });
            return results;
        });
    }

    private List<SearchResult> walkAndSearch(String pathStr, SearchFunction function) throws IOException {
        Path root = sandbox.resolve(pathStr);
        List<SearchResult> allResults = new ArrayList<>();
        
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());

            for (Path file : javaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    List<SearchResult> results = function.search(cu);
                    String relativePath = sandbox.getRoot().relativize(file).toString();
                    
                    for (SearchResult res : results) {
                        allResults.add(new SearchResult(
                                relativePath,
                                extractClassName(cu, res.beginLine()),
                                res.elementName(),
                                res.beginLine(),
                                res.snippet()
                        ));
                    }
                } catch (Exception e) {
                    // Skip files that fail to parse
                }
            }
        }
        return allResults;
    }

    private String extractClassName(CompilationUnit cu, int line) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getBegin().map(p -> p.line).orElse(-1) <= line && c.getEnd().map(p -> p.line).orElse(-1) >= line)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .findFirst()
                .orElse("Unknown");
    }

    @FunctionalInterface
    private interface SearchFunction {
        List<SearchResult> search(CompilationUnit cu);
    }
}
