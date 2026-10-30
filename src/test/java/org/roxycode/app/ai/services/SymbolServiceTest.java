package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;

class SymbolServiceTest {

    private static final String NL = String.valueOf((char) 10);

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private GrepService grepService;
    private SymbolService symbolService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
        grepService = mock(GrepService.class);
        symbolService = new SymbolService(projectService, grepService, AiConfig.SHARED_OBJECT_MAPPER);
    }

    private static String tagJson(String name, Path path, int line, Integer end, String kind, String scope,
            String signature) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("_type", "tag");
            if (name != null) {
                map.put("name", name);
            }
            if (path != null) {
                map.put("path", path.toString());
            }
            map.put("line", line);
            if (end != null) {
                map.put("end", end);
            }
            if (kind != null) {
                map.put("kind", kind);
            }
            if (scope != null) {
                map.put("scope", scope);
            }
            if (signature != null) {
                map.put("signature", signature);
            }
            return AiConfig.SHARED_OBJECT_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void testGetFileOutlineFiltersNoiseSortsByStartLineAndResolvesEndLineFallbacks() throws IOException {
        SymbolInfo normalized = new SymbolInfo(null, null, null, 5, 10, null, null);
        assertEquals("", normalized.name());
        assertEquals("", normalized.signature());

        Path file = tempDir.resolve("ServiceTest.java");
        Files.writeString(file,
                String.join(NL, "package demo;", "import java.util.List;", "class ServiceTest {",
                        "    private int count;", "    void firstTest() {", "        count++;", "    }",
                        "    void secondTest() {", "        count--;", "    }", "}"));

        List<List<String>> capturedCommands = new ArrayList<>();
        String ctagsJson = String.join(NL,
                AiConfig.SHARED_OBJECT_MAPPER
                        .writeValueAsString(Map.of("_type", "ptag", "name", "JSON_OUTPUT_VERSION")),
                tagJson("demo", null, 1, null, "package", null, null),
                tagJson("java.util.List", null, 2, null, "import", null, null), "not-valid-json",
                tagJson("invalidLine", null, 0, null, "method", null, null),
                tagJson("secondTest", null, 8, null, "method", "ServiceTest", "()"),
                tagJson("ServiceTest", null, 3, 11, "class", null, null),
                tagJson("count", null, 4, null, "field", "ServiceTest", null),
                tagJson("firstTest", null, 5, 7, "method", "ServiceTest", "()"));

        symbolService.setCtagsRunner(cmd -> {
            capturedCommands.add(cmd);
            return new ProcessResult(0, ctagsJson, "", false);
        });

        List<SymbolInfo> outline = symbolService.getFileOutline("ServiceTest.java");
        assertEquals(1, capturedCommands.size());
        assertTrue(capturedCommands.get(0).contains("--fields=+neKSs"));
        assertEquals(4, outline.size());

        assertEquals("ServiceTest", outline.get(0).name());
        assertEquals("class", outline.get(0).kind());
        assertEquals(3, outline.get(0).startLine());
        assertEquals(11, outline.get(0).endLine());

        assertEquals("count", outline.get(1).name());
        assertEquals("field", outline.get(1).kind());
        assertEquals(4, outline.get(1).startLine());
        assertEquals(4, outline.get(1).endLine());

        assertEquals("firstTest", outline.get(2).name());
        assertEquals(5, outline.get(2).startLine());
        assertEquals(7, outline.get(2).endLine());

        assertEquals("secondTest", outline.get(3).name());
        assertEquals(8, outline.get(3).startLine());
        assertEquals(11, outline.get(3).endLine());

        symbolService.setCtagsRunner(cmd -> new ProcessResult(0, "", "", true));
        assertTrue(symbolService.getFileOutline("ServiceTest.java").isEmpty());

        symbolService.setCtagsRunner(cmd -> new ProcessResult(-1, "", "ctags not found", false));
        assertTrue(symbolService.getFileOutline("ServiceTest.java").isEmpty());

        assertThrows(IllegalArgumentException.class, () -> symbolService.getFileOutline("Missing.java"));
        Files.createDirectory(tempDir.resolve("adir"));
        assertThrows(IllegalArgumentException.class, () -> symbolService.getFileOutline("adir"));

        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> symbolService.getFileOutline("ServiceTest.java"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testReadSymbolSupportsExactMatchScopeQualifiedMatchOverloadsAndPrecedingContext() throws IOException {
        Path file = tempDir.resolve("Calculator.java");
        List<String> lines = List.of("package calc;", "", "public class Calculator {", "    public Calculator() {",
                "    }", "", "    /**", "     * Adds two numbers.", "     */", "    @Deprecated",
                "    public int add(int a, int b) {", "        return a + b;", "    }", "", "    // Overloaded add",
                "    public double add(double a, double b) {", "        return a + b;", "    }", "",
                "    static class Inner {", "        void compute() {", "        }", "    }", "}");
        Files.writeString(file, String.join(NL, lines));

        String ctagsJson = String.join(NL, tagJson("Calculator", file, 3, 24, "class", null, null),
                tagJson("Calculator", file, 4, 5, "method", "Calculator", "()"),
                tagJson("add", file, 11, 13, "method", "Calculator", "(int a, int b)"),
                tagJson("add", file, 16, 18, "method", "Calculator", "(double a, double b)"),
                tagJson("Inner", file, 20, 23, "class", "Calculator", null),
                tagJson("compute", file, 21, 22, "method", "Calculator.Inner", "()"));
        symbolService.setCtagsRunner(cmd -> new ProcessResult(0, ctagsJson, "", false));

        String addOutput = symbolService.readSymbol("Calculator.java", "add");
        String expectedAdd = String.join(NL, "7:     /**", "8:      * Adds two numbers.", "9:      */",
                "10:     @Deprecated", "11:     public int add(int a, int b) {", "12:         return a + b;",
                "13:     }") + NL + NL
                + String.join(NL, "15:     // Overloaded add", "16:     public double add(double a, double b) {",
                        "17:         return a + b;", "18:     }");
        assertEquals(expectedAdd, addOutput);
        assertEquals(expectedAdd, symbolService.readSymbol("Calculator.java", "Calculator.add"));

        String ctorOnly = symbolService.readSymbol("Calculator.java", "Calculator.Calculator");
        assertEquals(String.join(NL, "4:     public Calculator() {", "5:     }"), ctorOnly);

        String fullClass = symbolService.readSymbol("Calculator.java", "Calculator");
        assertTrue(fullClass.startsWith("3: public class Calculator {"));
        assertTrue(fullClass.endsWith("24: }"));
        assertFalse(fullClass.substring(fullClass.indexOf("4:     public Calculator() {") + 1)
                .contains("4:     public Calculator() {"));

        String innerCompute = symbolService.readSymbol("Calculator.java", "Inner.compute");
        assertEquals(String.join(NL, "21:         void compute() {", "22:         }"), innerCompute);
        assertEquals(innerCompute, symbolService.readSymbol("Calculator.java", "Calculator.Inner.compute"));

        assertTrue(symbolService.readSymbol("Calculator.java", "nonExistent")
                .startsWith("Error: Symbol 'nonExistent' not found"));
        assertTrue(symbolService.readSymbol("Calculator.java", " ").startsWith("Error: Symbol ' ' not found"));
        assertTrue(symbolService.readSymbol("Calculator.java", null).startsWith("Error: Symbol '' not found"));
    }

    @Test
    void testReadSymbolsBatchArrayAndListOverloads() throws Exception {
        Path file = tempDir.resolve("Batch.java");
        Files.writeString(file,
                String.join(NL, "class Batch {", "    void alpha() {", "    }", "    void beta() {", "    }", "}"));
        symbolService.setCtagsRunner(
                cmd -> new ProcessResult(0, String.join(NL, tagJson("alpha", null, 2, 3, "method", "Batch", "()"),
                        tagJson("beta", null, 4, 5, "method", "Batch", "()")), "", false));

        assertTrue(symbolService.readSymbols("Batch.java", (String[]) null).isEmpty());
        assertTrue(symbolService.readSymbols("Batch.java").isEmpty());

        String originalContent = Files.readString(file);
        clearInvocations(projectService);
        symbolService.setCtagsRunner(cmd -> {
            try {
                Files.writeString(file, "corrupted on disk after initial read");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new ProcessResult(0, String.join(NL, tagJson("alpha", null, 2, 3, "method", "Batch", "()"),
                    tagJson("beta", null, 4, 5, "method", "Batch", "()")), "", false);
        });

        Map<String, String> batchResult = symbolService.readSymbols("Batch.java", "alpha", "beta", "missing", "alpha",
                "");
        verify(projectService, times(1)).resolvePath("Batch.java");
        assertEquals(List.of("alpha", "beta", "missing"), new ArrayList<>(batchResult.keySet()));
        assertEquals(String.join(NL, "2:     void alpha() {", "3:     }"), batchResult.get("alpha"));
        assertEquals(String.join(NL, "4:     void beta() {", "5:     }"), batchResult.get("beta"));
        assertTrue(batchResult.get("missing").startsWith("Error: Symbol 'missing' not found"));
        Files.writeString(file, originalContent);

        Map<String, String> missingFileBatch = symbolService.readSymbols("NoSuchFile.java", "alpha", "beta");
        assertTrue(missingFileBatch.get("alpha").startsWith("Error:"));
        assertTrue(missingFileBatch.get("beta").startsWith("Error:"));
    }

    @Test
    void testFindSymbolNarrowsWithGrepCapsFilesAndMatchesAcrossProject() throws IOException {
        Path fileA = tempDir.resolve("A.java");
        Path fileB = tempDir.resolve("B.java");
        Files.writeString(fileA, String.join(NL, "class A {", "    void targetMethod() {}", "}"));
        Files.writeString(fileB,
                String.join(NL, "class B {", "    void targetMethod(int x) {}", "    void other() {}", "}"));

        when(grepService.findMatchingFiles("targetMethod")).thenReturn(List.of("A.java", "B.java", "Missing.java"));

        String ctagsOutput = String.join(NL, tagJson("targetMethod", fileB, 2, 2, "method", "B", "(int x)"),
                tagJson("other", fileB, 3, 3, "method", "B", "()"),
                tagJson("targetMethod", fileA, 2, 2, "method", "A", "()"));

        symbolService.setCtagsRunner(cmd -> new ProcessResult(0, ctagsOutput, "", false));

        List<SymbolInfo> allMatches = symbolService.findSymbol("targetMethod");
        assertEquals(2, allMatches.size());
        assertEquals("A.java", allMatches.get(0).path());
        assertEquals("A", allMatches.get(0).scope());
        assertEquals("B.java", allMatches.get(1).path());
        assertEquals("B", allMatches.get(1).scope());

        List<SymbolInfo> scopedMatches = symbolService.findSymbol("B.targetMethod");
        assertEquals(1, scopedMatches.size());
        assertEquals("B.java", scopedMatches.get(0).path());
        assertEquals("(int x)", scopedMatches.get(0).signature());

        List<String> thirtyFiles = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String rel = "File" + i + ".java";
            Files.writeString(tempDir.resolve(rel), "class File" + i + " { void capped() {} }");
            thirtyFiles.add(rel);
        }
        when(grepService.findMatchingFiles("capped")).thenReturn(thirtyFiles);
        List<Integer> batchFileCounts = new ArrayList<>();
        symbolService.setCtagsRunner(cmd -> {
            batchFileCounts.add(cmd.size() - 5);
            return new ProcessResult(0, "", "", false);
        });
        symbolService.findSymbol("capped");
        assertEquals(List.of(SymbolService.MAX_FIND_SYMBOL_FILES), batchFileCounts);

        assertTrue(symbolService.findSymbol(null).isEmpty());
        assertTrue(symbolService.findSymbol("   ").isEmpty());
        when(projectService.hasActiveProject()).thenReturn(false);
        assertTrue(symbolService.findSymbol("targetMethod").isEmpty());
    }
}
