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
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.service.ProjectService;

class SymbolServiceTest {

    private static final String NL = "\n";

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
        lenient().when(projectService.resolveReadPath(any())).thenCallRealMethod();
        lenient().when(projectService.formatReadDisplayPath(any(), any())).thenCallRealMethod();
        lenient().when(projectService.findReferenceFolderMount(any())).thenCallRealMethod();
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
        verify(projectService, times(1)).resolveReadPath("Batch.java");
        assertEquals(List.of("alpha", "beta", "missing"), new ArrayList<>(batchResult.keySet()));
        assertEquals(String.join(NL, "2:     void alpha() {", "3:     }"), batchResult.get("alpha"));
        assertEquals(String.join(NL, "4:     void beta() {", "5:     }"), batchResult.get("beta"));
        assertTrue(batchResult.get("missing").startsWith("Error: Symbol 'missing' not found"));
        Files.writeString(file, originalContent);

        Path appFile = tempDir.resolve("App.java");
        Files.writeString(appFile, String.join(NL, "class App {", "    void main() {", "    }", "}"));
        symbolService.setCtagsRunner(
                cmd -> new ProcessResult(0, tagJson("main", null, 2, 3, "method", "App", "()"), "", false));
        clearInvocations(projectService);
        assertEquals(String.join(NL, "2:     void main() {", "3:     }"), symbolService.readSymbol("App.java", "main"));
        verify(projectService, times(1)).resolveReadPath("App.java");

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

    @Test
    void testFindUsagesFiltersDefinitionsCommentsImportsAndEnrichesWithSingleBatchCtags() throws IOException {
        Path toolFile = Files.createDirectories(tempDir.resolve("src/main/java/demo")).resolve("JexlTool.java");
        Path callerFile = Files.createDirectories(tempDir.resolve("src/main/java/app")).resolve("Caller.java");
        Path otherFile = Files.createDirectories(tempDir.resolve("src/main/java/other")).resolve("OtherTool.java");
        Path testFile = Files.createDirectories(tempDir.resolve("src/test/java/app")).resolve("CallerTest.java");

        Files.writeString(toolFile, String.join(NL, "package demo;", "public class JexlTool {",
                "    public String executeJexl(String script) {", "        return script;", "    }", "}"));
        Files.writeString(callerFile,
                String.join(NL, "package app;", "import demo.JexlTool;", "public class Caller {",
                        "    // executeJexl comment should be ignored", "    void run(JexlTool tool) {",
                        "        tool.executeJexl('1+1');", "    }", "}"));
        Files.writeString(otherFile,
                String.join(NL, "package other;", "public class OtherTool {", "    public void executeJexl() {}", "}"));
        Files.writeString(testFile, String.join(NL, "package app;", "class CallerTest {", "    void testRun() {",
                "        new JexlTool().executeJexl('test');", "    }", "}"));

        List<String> vimgrepLines = List.of("src/main/java/app/Caller.java:2:8:import demo.JexlTool;",
                "src/main/java/app/Caller.java:4:8:    // executeJexl comment should be ignored",
                "src/main/java/demo/JexlTool.java:3:19:    public String executeJexl(String script) {",
                "src/main/java/other/OtherTool.java:3:17:    public void executeJexl() {}",
                "./src/test/java/app/CallerTest.java:4:24:        new JexlTool().executeJexl('test');",
                "src/main/java/app/Caller.java:6:14:        tool.executeJexl('1+1');",
                "src/main/java/app/Caller.java:6:28:        tool.executeJexl('1+1');");
        when(grepService.grepWordMatches("executeJexl", ".", true)).thenReturn(vimgrepLines);

        List<List<String>> ctagsCalls = new ArrayList<>();
        String ctagsOutput = String.join(NL, tagJson("JexlTool", toolFile, 2, 6, "class", null, null),
                tagJson("executeJexl", toolFile, 3, 5, "method", "JexlTool", "(String script)"),
                tagJson("Caller", callerFile, 3, 8, "class", null, null),
                tagJson("run", callerFile, 5, 7, "method", "Caller", "(JexlTool tool)"),
                tagJson("OtherTool", otherFile, 2, 4, "class", null, null),
                tagJson("executeJexl", otherFile, 3, 3, "method", "OtherTool", "()"),
                tagJson("CallerTest", testFile, 2, 6, "class", null, null),
                tagJson("testRun", testFile, 3, 5, "method", "CallerTest", "()"));
        symbolService.setCtagsRunner(cmd -> {
            ctagsCalls.add(cmd);
            return new ProcessResult(0, ctagsOutput, "", false);
        });

        List<SymbolUsage> usages = symbolService.findUsages("JexlTool.executeJexl");
        assertEquals(1, ctagsCalls.size());
        assertEquals(2, usages.size());

        SymbolUsage prodUsage = usages.get(0);
        assertEquals("src/main/java/app/Caller.java", prodUsage.getPath());
        assertEquals(6, prodUsage.getLine());
        assertEquals(14, prodUsage.getColumn());
        assertEquals("tool.executeJexl('1+1');", prodUsage.getSnippet());
        assertEquals("Caller.run", prodUsage.getEnclosingSymbol());
        assertFalse(prodUsage.isTestFile());

        SymbolUsage testUsage = usages.get(1);
        assertEquals("src/test/java/app/CallerTest.java", testUsage.getPath());
        assertEquals(4, testUsage.getLine());
        assertEquals("CallerTest.testRun", testUsage.getEnclosingSymbol());
        assertTrue(testUsage.isTestFile());

        assertTrue(symbolService.findUsages(null).isEmpty());
        assertTrue(symbolService.findUsages("   ").isEmpty());
    }

    @Test
    void testFindRelatedTestsCoversMirroredTier1ConventionsTier2FallbackAndTestFileSelfCheck() throws IOException {
        Path existingTest = Files.createDirectories(tempDir.resolve("src/test/java/org/roxycode/service"))
                .resolve("SymbolServiceTest.java");
        Files.writeString(existingTest, "class SymbolServiceTest {}");

        assertEquals(List.of("src/test/java/org/roxycode/service/SymbolServiceTest.java"),
                symbolService.findRelatedTests("src/test/java/org/roxycode/service/SymbolServiceTest.java"));

        when(grepService.listFiles()).thenReturn(List.of("src/main/java/org/roxycode/service/SymbolService.java",
                "src/test/java/org/roxycode/other/SymbolServiceTest.java",
                "src/test/java/org/roxycode/service/SymbolServiceTest.java",
                "src/test/java/org/roxycode/service/SymbolServiceIT.java", "tests/service/test_symbol_service.py",
                "frontend/src/components/Button.spec.tsx"));
        when(grepService.findMatchingFiles("\\bSymbolService\\b"))
                .thenReturn(List.of("src/main/java/org/roxycode/ai/JexlServiceRegistry.java",
                        "src/test/java/org/roxycode/ai/JexlServiceRegistryTest.java",
                        "src/test/java/org/roxycode/service/SymbolServiceTest.java"));

        List<String> relatedJava = symbolService
                .findRelatedTests("src/main/java/org/roxycode/service/SymbolService.java");
        assertEquals(List.of("src/test/java/org/roxycode/service/SymbolServiceIT.java",
                "src/test/java/org/roxycode/service/SymbolServiceTest.java",
                "src/test/java/org/roxycode/other/SymbolServiceTest.java",
                "src/test/java/org/roxycode/ai/JexlServiceRegistryTest.java"), relatedJava);

        assertTrue(ProjectAnalysisService.matchesTestNamingConvention("test_symbol_service.py", "symbol_service"));
        assertTrue(ProjectAnalysisService.matchesTestNamingConvention("symbol_service_test.go", "symbol_service"));
        assertTrue(ProjectAnalysisService.matchesTestNamingConvention("Button.spec.tsx", "Button"));
        assertTrue(ProjectAnalysisService.matchesTestNamingConvention("Button.test.ts", "Button"));
        assertTrue(ProjectAnalysisService.matchesTestNamingConvention("engine_tests.rs", "engine"));
        assertFalse(ProjectAnalysisService.matchesTestNamingConvention("OtherTest.java", "SymbolService"));

        assertTrue(symbolService.findRelatedTests(null).isEmpty());
        assertTrue(symbolService.findRelatedTests("   ").isEmpty());
    }

    @Test
    void testReferenceFolderOutlineReadSymbolFindSymbolFindUsagesAndRelatedTests() throws IOException {
        Path refRoot = Files.createDirectories(tempDir.resolve("ext-billing"));
        Path invoiceFile = Files.createDirectories(refRoot.resolve("src/main/java/billing")).resolve("Invoice.java");
        Path invoiceTestFile = Files.createDirectories(refRoot.resolve("src/test/java/billing"))
                .resolve("InvoiceTest.java");
        Files.writeString(invoiceFile, String.join(NL, "package billing;", "public class Invoice {",
                "    public int total() {", "        return 42;", "    }", "}"));
        Files.writeString(invoiceTestFile, String.join(NL, "package billing;", "class InvoiceTest {",
                "    void testTotal() {", "        new Invoice().total();", "    }", "}"));

        ProjectService.ReferenceFolderMount mount = new ProjectService.ReferenceFolderMount("billing-service",
                refRoot.toString(), refRoot, false);
        when(projectService.getReferenceFolderMounts()).thenReturn(List.of(mount));

        String ctagsJson = String.join(NL, tagJson("Invoice", invoiceFile, 2, 6, "class", null, null),
                tagJson("total", invoiceFile, 3, 5, "method", "Invoice", "()"),
                tagJson("InvoiceTest", invoiceTestFile, 2, 6, "class", null, null),
                tagJson("testTotal", invoiceTestFile, 3, 5, "method", "InvoiceTest", "()"));
        symbolService.setCtagsRunner(cmd -> new ProcessResult(0, ctagsJson, "", false));

        List<SymbolInfo> outline = symbolService.getFileOutline("@billing-service/src/main/java/billing/Invoice.java");
        assertEquals(2, outline.size());
        assertEquals("@billing-service/src/main/java/billing/Invoice.java", outline.get(0).path());
        assertEquals("@billing-service/src/main/java/billing/Invoice.java", outline.get(1).path());

        String body = symbolService.readSymbol("@billing-service/src/main/java/billing/Invoice.java", "total");
        assertEquals(String.join(NL, "3:     public int total() {", "4:         return 42;", "5:     }"), body);

        when(grepService.findMatchingFiles("total", "@billing-service", null))
                .thenReturn(List.of("@billing-service/src/main/java/billing/Invoice.java"));
        List<SymbolInfo> foundSymbols = symbolService.findSymbol("Invoice.total", "@billing-service");
        assertEquals(1, foundSymbols.size());
        assertEquals("@billing-service/src/main/java/billing/Invoice.java", foundSymbols.get(0).path());

        when(grepService.grepWordMatches("total", "@billing-service", true))
                .thenReturn(List.of("@billing-service/src/main/java/billing/Invoice.java:3:16:    public int total() {",
                        "@billing-service/src/test/java/billing/InvoiceTest.java:4:23:        new Invoice().total();"));
        List<SymbolUsage> usages = symbolService.findUsages("Invoice.total", "@billing-service");
        assertEquals(1, usages.size());
        assertEquals("@billing-service/src/test/java/billing/InvoiceTest.java", usages.get(0).path());
        assertEquals("InvoiceTest.testTotal", usages.get(0).enclosingSymbol());
        assertTrue(usages.get(0).testFile());

        when(grepService.listFiles("@billing-service"))
                .thenReturn(List.of("@billing-service/src/main/java/billing/Invoice.java",
                        "@billing-service/src/test/java/billing/InvoiceTest.java"));
        assertEquals(List.of("@billing-service/src/test/java/billing/InvoiceTest.java"),
                symbolService.findRelatedTests("@billing-service/src/main/java/billing/Invoice.java"));
    }
}