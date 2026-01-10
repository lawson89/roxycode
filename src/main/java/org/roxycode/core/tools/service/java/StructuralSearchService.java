package org.roxycode.core.tools.service.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.roxycode.core.tools.service.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for performing structural and semantic searches on Java source code.
 */
@ScriptService("structuralSearchService")
@Singleton
@LLMDoc("Service for performing structural and semantic searches on Java source code.")
public class StructuralSearchService {

    @Inject
    Sandbox sandbox;

    @Inject
    com.github.javaparser.JavaParser javaParser;

    @Inject
    ParserConfiguration parserConfiguration;

    /**
     * Initializes the Java analysis engine with a symbol solver.
     */
    public void init() {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        parserConfiguration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
    }

    /**
     * Finds empty catch blocks in the specified directory.
     *
     * @param pathStr The directory to search in, relative to the sandbox root.
     * @return A list of SearchResult objects representing the empty catch blocks.
     * @throws IOException If an I/O error occurs during file walking.
     */
    @LLMDoc("Finds empty catch blocks in the specified directory")
    public List<SearchResult> findEmptyCatchBlocks(String pathStr) throws IOException {
        return search(pathStr, (cu, filePath) -> cu.findAll(CatchClause.class).stream()
                .filter(c -> c.getBody().getStatements().isEmpty())
                .map(c -> new SearchResult(filePath, extractClassName(cu), "catch", c.getBegin().map(p -> p.line).orElse(-1), c.toString()))
                .collect(Collectors.toList()));
    }

    /**
     * Finds methods or classes marked with @Deprecated that lack a Javadoc comment.
     *
     * @param pathStr The directory to search in, relative to the sandbox root.
     * @return A list of SearchResult objects representing deprecated elements without Javadoc.
     * @throws IOException If an I/O error occurs during file walking.
     */
    @LLMDoc("Finds methods or classes marked with @Deprecated that lack a Javadoc comment")
    public List<SearchResult> findDeprecatedWithoutJavadoc(String pathStr) throws IOException {
        return search(pathStr, (cu, filePath) -> {
            List<SearchResult> results = new ArrayList<>();
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                if (t.isAnnotationPresent("Deprecated") && !t.getJavadoc().isPresent()) {
                    results.add(new SearchResult(filePath, extractClassName(cu), t.getNameAsString(), t.getBegin().map(p -> p.line).orElse(-1), t.getNameAsString()));
                }
            }
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if (m.isAnnotationPresent("Deprecated") && !m.getJavadoc().isPresent()) {
                    results.add(new SearchResult(filePath, extractClassName(cu), m.getNameAsString(), m.getBegin().map(p -> p.line).orElse(-1), m.getDeclarationAsString()));
                }
            }
            return results;
        });
    }

    /**
     * Finds methods with more than the specified number of parameters.
     *
     * @param pathStr   The directory to search in, relative to the sandbox root.
     * @param threshold The maximum number of parameters allowed.
     * @return A list of SearchResult objects representing methods with too many parameters.
     * @throws IOException If an I/O error occurs during file walking.
     */
    @LLMDoc("Finds methods with more than the specified number of parameters")
    public List<SearchResult> findMethodsWithTooManyParameters(String pathStr, int threshold) throws IOException {
        return search(pathStr, (cu, filePath) -> cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getParameters().size() > threshold)
                .map(m -> new SearchResult(filePath, extractClassName(cu), m.getNameAsString(), m.getBegin().map(p -> p.line).orElse(-1), m.getDeclarationAsString()))
                .collect(Collectors.toList()));
    }

    /**
     * Finds classes with more than the specified number of lines.
     *
     * @param pathStr       The directory to search in, relative to the sandbox root.
     * @param lineThreshold The maximum number of lines allowed.
     * @return A list of SearchResult objects representing classes that are too large.
     * @throws IOException If an I/O error occurs during file walking.
     */
    @LLMDoc("Finds classes with more than the specified number of lines")
    public List<SearchResult> findLargeClasses(String pathStr, int lineThreshold) throws IOException {
        return search(pathStr, (cu, filePath) -> {
            List<SearchResult> results = new ArrayList<>();
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                int startLine = t.getBegin().map(p -> p.line).orElse(0);
                int endLine = t.getEnd().map(p -> p.line).orElse(0);
                int lines = endLine - startLine + 1;
                if (lines > lineThreshold) {
                    results.add(new SearchResult(filePath, extractClassName(cu), t.getNameAsString(), startLine, t.getNameAsString()));
                }
            }
            return results;
        });
    }

    private List<SearchResult> search(String pathStr, SearchFunction searchFunction) throws IOException {
        Path root = sandbox.resolve(pathStr);
        List<SearchResult> allResults = new ArrayList<>();
        Files.walk(root)
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        javaParser.parse(p).getResult().ifPresent(cu -> {
                            allResults.addAll(searchFunction.search(cu, sandbox.getRoot().relativize(p).toString()));
                        });
                    } catch (IOException e) {
                        // Ignore files that cannot be read
                    }
                });
        return allResults;
    }

    private String extractClassName(CompilationUnit cu) {
        return cu.findAll(TypeDeclaration.class).stream()
                .map(TypeDeclaration::getNameAsString)
                .findFirst()
                .orElse("Unknown");
    }

    private interface SearchFunction {
        List<SearchResult> search(CompilationUnit cu, String filePath);
    }
}
