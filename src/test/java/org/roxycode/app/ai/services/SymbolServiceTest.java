package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
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
    void testSymbolInfoRecordNormalizationAndJavaBeanGetters() throws Exception {
        SymbolInfo normalized = new SymbolInfo(null, null, null, 5, 10, null, null);
        assertEquals("", normalized.name());
        assertEquals("", normalized.getName());
        assertEquals("", normalized.kind());
        assertEquals("", normalized.getKind());
        assertEquals("", normalized.path());
        assertEquals("", normalized.getPath());
        assertEquals(5, normalized.startLine());
        assertEquals(5, normalized.getStartLine());
        assertEquals(10, normalized.endLine());
        assertEquals(10, normalized.getEndLine());
        assertEquals("", normalized.scope());
        assertEquals("", normalized.getScope());
        assertEquals("", normalized.signature());
        assertEquals("", normalized.getSignature());

        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file,
                String.join(NL, "public class Sample {", "    public void greet(String who) {", "    }", "}"));
        symbolService.setCtagsRunner(cmd -> new ProcessResult(0,
                tagJson("greet", file, 2, 3, "method", "Sample", "(String who)"), "", false));

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(symbolService);
        JexlTool jexlTool = new JexlTool(registry, null, null, null);
        String json = jexlTool.executeJexl("""
                var syms = symbolService.getFileOutline('Sample.java');
                var s = syms[0];
                {
                  'name': s.name,
                  'kind': s.kind,
                  'path': s.path,
                  'startLine': s.startLine,
                  'endLine': s.endLine,
                  'scope': s.scope,
                  'signature': s.signature
                }
                """);
        JsonNode root = AiConfig.SHARED_OBJECT_MAPPER.readTree(json);
        assertEquals("greet", root.get("name").asText());
        assertEquals("method", root.get("kind").asText());
        assertEquals("Sample.java", root.get("path").asText());
        assertEquals(2, root.get("startLine").asInt());
        assertEquals(3, root.get("endLine").asInt());
        assertEquals("Sample", root.get("scope").asText());
        assertEquals("(String who)", root.get("signature").asText());
    }

    @Test
    void testGetFileOutlineFiltersNoiseSortsByStartLineAndResolvesEndLineFallbacks() throws IOException {
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
    }

    @Test
    void testGetFileOutlineErrorAndFallbackCases() throws IOException {
        Path file = tempDir.resolve("EmptyOutline.java");
        Files.writeString(file, "class EmptyOutline {}");

        symbolService.setCtagsRunner(cmd -> new ProcessResult(0, "", "", true));
        assertTrue(symbolService.getFileOutline("EmptyOutline.java").isEmpty());

        symbolService.setCtagsRunner(cmd -> {
            throw new IOException("ctags not found");
        });
        assertTrue(symbolService.getFileOutline("EmptyOutline.java").isEmpty());

        assertThrows(IllegalArgumentException.class, () -> symbolService.getFileOutline("Missing.java"));
        Files.createDirectory(tempDir.resolve("adir"));
        assertThrows(IllegalArgumentException.class, () -> symbolService.getFileOutline("adir"));
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

        String fullInnerCompute = symbolService.readSymbol("Calculator.java", "Calculator.Inner.compute");
        assertEquals(innerCompute, fullInnerCompute);

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
        assertTrue(symbolService.readSymbols("Batch.java", (List<String>) null).isEmpty());
        assertTrue(symbolService.readSymbols("Batch.java", List.of()).isEmpty());

        String originalContent = Files.readString(file);
        clearInvocations(projectService);
        symbolService.setCtagsRunner(cmd -> {
            Files.writeString(file, "corrupted on disk after initial read");
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

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(symbolService);
        JexlTool jexlTool = new JexlTool(registry, null, null, null);
        JsonNode jexlNode = AiConfig.SHARED_OBJECT_MAPPER
                .readTree(jexlTool.executeJexl("symbolService.readSymbols('Batch.java', ['alpha', 'beta'])"));
        assertEquals(String.join(NL, "2:     void alpha() {", "3:     }"), jexlNode.get("alpha").asText());
        assertEquals(String.join(NL, "4:     void beta() {", "5:     }"), jexlNode.get("beta").asText());
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

    @Test
    void testCtagsEntryDeserializationAndSharedRunnerEdgeCases() throws Exception {
        String rawJson = """
                {"_type":"tag","name":"myFunc","path":"/tmp/Demo.java","kind":"method","line":12,"end":20,"scope":"Demo","signature":"(int a)","extraIgnoredField":true}
                """;
        CtagsRunner.CtagsEntry entry = AiConfig.SHARED_OBJECT_MAPPER.readValue(rawJson, CtagsRunner.CtagsEntry.class);
        assertEquals("tag", entry.type());
        assertEquals("myFunc", entry.name());
        assertEquals("/tmp/Demo.java", entry.path());
        assertEquals("method", entry.kind());
        assertEquals(12, entry.line());
        assertEquals(20, entry.end());
        assertEquals("Demo", entry.scope());
        assertEquals("(int a)", entry.signature());

        CtagsRunner runner = cmd -> new ProcessResult(0, rawJson, "", false);
        assertTrue(runner.runAndParse(null, "--fields=+n", null, "cancelled").isEmpty());
        assertTrue(runner.runAndParse(List.of(), "--fields=+n", null, "cancelled").isEmpty());
        assertNull(CtagsRunner.resolveTargetPath(null, List.of(tempDir.resolve("A.java"), tempDir.resolve("B.java"))));
    }

    @Test
    void testCancellationPropagation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> symbolService.getFileOutline("Any.java"));
        } finally {
            Thread.interrupted();
        }
    }
}
