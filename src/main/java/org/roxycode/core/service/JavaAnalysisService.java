package org.roxycode.core.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface JavaAnalysisService {
    /**
     * Parses a Java file and returns a summary of its structure.
     */
    JavaFileSummary analyzeFile(Path path) throws IOException;

    /**
     * Gets the source code of a specific method.
     */
    Optional<String> getMethodSource(Path path, String className, String methodName);

    record JavaFileSummary(List<ClassSummary> classes, List<String> imports) {}
    record ClassSummary(String name, List<MethodSummary> methods, boolean isInterface) {}
    record MethodSummary(String name, String signature, int beginLine, int endLine) {}
}
