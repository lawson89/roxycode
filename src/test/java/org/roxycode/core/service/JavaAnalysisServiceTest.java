package org.roxycode.core.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class JavaAnalysisServiceTest {

    @Inject
    JavaAnalysisService javaAnalysisService;

    @Test
    void testAnalyzeFile() throws IOException {
        Path path = Paths.get("src/main/java/org/roxycode/core/service/JavaAnalysisService.java");
        JavaAnalysisService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path);

        assertNotNull(summary);
        assertFalse(summary.classes().isEmpty());
        assertEquals("JavaAnalysisService", summary.classes().get(0).name());
        assertTrue(summary.classes().get(0).isInterface());
        
        // It has 2 methods defined in the interface
        assertEquals(2, summary.classes().get(0).methods().size());
    }

    @Test
    void testGetMethodSource() {
        Path path = Paths.get("src/main/java/org/roxycode/core/service/JavaAnalysisServiceImpl.java");
        Optional<String> source = javaAnalysisService.getMethodSource(path, "JavaAnalysisServiceImpl", "summarizeMethod");

        assertTrue(source.isPresent());
        assertTrue(source.get().contains("MethodSummary summarizeMethod"));
        assertTrue(source.get().contains("return new MethodSummary"));
    }
}
