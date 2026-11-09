package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingTestUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.ApplicationEventPublisher;

class FileEditorServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private ApplicationEventPublisher eventPublisher;
    private SymbolService symbolService;
    private FileEditorService fileEditorService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        symbolService = mock(SymbolService.class);
        fileEditorService = new FileEditorService(projectService, eventPublisher, symbolService);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
    }

    private String writeAndReplace(String relPath, String initial, String target, String replacement)
            throws IOException {
        Path file = Files.writeString(tempDir.resolve(relPath), initial);
        assertTrue(fileEditorService.replaceBlock(relPath, target, replacement).success());
        return Files.readString(file);
    }

    private static void assertEditFails(EditorResult result, String... expectedHints) {
        assertFalse(result.success());
        SwingTestUtils.assertContainsAll(result.errorHint(), expectedHints);
    }

    @Test
    void testReplaceBlockIndentationCrlfAndDiagnostics() throws IOException {
        assertEquals("def calculate(x):\n    if x == 2:\n        return 20\n    return 0\n",
                writeAndReplace("script.py", "def calculate(x):\n    if x   ==   1:\n        return 10\n    return 0\n",
                        "    if x == 1:\n        return 10", "    if x == 2:\n        return 20"));
        assertEquals("server:\n  host: 0.0.0.0\n  port: 9090\n",
                writeAndReplace("config.yaml", "server:\n  host:   localhost\n  port: 8080\n",
                        "host: localhost\n  port: 8080", "  host: 0.0.0.0\n  port: 9090"));
        assertEquals("class A {\r\n    int x = 10;\r\n}\r\n", writeAndReplace("crlf.java",
                "class A {\r\n    int x = 1;\r\n}\r\n", "    int x = 1;", "    int x = 10;"));

        Path filePath = Files.writeString(tempDir.resolve("test.java"), "int   a = 1;\nint     a   = 1;");
        assertEditFails(fileEditorService.replaceBlock("test.java", "int a = 1;", "int a = 2;"), "not unique", "1",
                "2");
        assertEditFails(fileEditorService.replaceBlock("test.java", "missingTarget", "x"),
                "TargetBlock not found in file: test.java (file has 2 lines)");
        assertEditFails(fileEditorService.replaceBlock("nonexistent.java", "foo", "bar"),
                "File does not exist: nonexistent.java");
        assertTrue(Files.exists(filePath));
    }

    @Test
    void testReplaceLinesAndInsertAtLineFormattingAndBounds() throws IOException {
        Path filePath = Files.write(tempDir.resolve("test.txt"), List.of("Line 1", "Line 2", "Line 3", "Line 4"));
        assertTrue(fileEditorService.replaceLines("test.txt", 2, 3, "New Content").success());
        assertEquals(List.of("Line 1", "New Content", "Line 4"), Files.readAllLines(filePath));

        assertEditFails(fileEditorService.replaceLines("test.txt", 2, 5, "Replacement"), "Invalid line range: 2 to 5");
        assertEditFails(fileEditorService.insertAtLine("test.txt", 6, "Inserted"), "Invalid line number: 6");

        Path crlfFile = Files.writeString(tempDir.resolve("crlf.txt"), "Line 1\r\nLine 2\r\n");
        fileEditorService.insertAtLine("crlf.txt", 2, "Inserted 1.5");
        assertEquals("Line 1\r\nInserted 1.5\r\nLine 2\r\n", Files.readString(crlfFile));

        Path noTrailingFile = Files.writeString(tempDir.resolve("no_trailing.txt"), "Alpha\nBeta");
        fileEditorService.replaceLines("no_trailing.txt", 2, 2, "BetaUpdated");
        assertEquals("Alpha\nBetaUpdated", Files.readString(noTrailingFile));
    }

    @Test
    void testMoveCopyDeleteAndMkdirsOperations() throws IOException {
        assertTrue(fileEditorService.writeFile("new_file.txt", "initial").success());
        verify(eventPublisher).publishEvent(new FileChangedEvent("new_file.txt", FileChangedEvent.ChangeType.CREATED));
        clearInvocations(eventPublisher);
        assertTrue(fileEditorService.writeFile("new_file.txt", "updated").success());
        verify(eventPublisher).publishEvent(new FileChangedEvent("new_file.txt", FileChangedEvent.ChangeType.MODIFIED));

        assertTrue(fileEditorService.mkdirs("nested/dir").success());
        assertTrue(fileEditorService.copyFile("new_file.txt", "copied/copy.txt").success());
        assertEquals("updated", Files.readString(tempDir.resolve("copied/copy.txt")));

        assertTrue(fileEditorService.moveFile("new_file.txt", "moved/renamed.txt").success());
        assertFalse(Files.exists(tempDir.resolve("new_file.txt")));
        assertTrue(fileEditorService.deleteFile("moved/renamed.txt").success());
        assertEditFails(fileEditorService.deleteFile("nested/dir"), "Cannot delete directory: nested/dir");
        assertThrows(SecurityException.class, () -> fileEditorService.moveFile("../outside.txt", "inside.txt"));

        assertThrows(SecurityException.class, () -> fileEditorService.writeFile("@billing-service/A.java", "x"));
        assertThrows(SecurityException.class,
                () -> fileEditorService.replaceBlock("@billing-service/A.java", "a", "b"));
        assertThrows(SecurityException.class, () -> fileEditorService.replaceBlocks("@billing-service/A.java",
                List.of(Map.of("target", "a", "replacement", "b"))));
        assertThrows(SecurityException.class,
                () -> fileEditorService.replaceLines("@billing-service/A.java", 1, 1, "x"));
        assertThrows(SecurityException.class, () -> fileEditorService.insertAtLine("@billing-service/A.java", 1, "x"));
        assertThrows(SecurityException.class, () -> fileEditorService.deleteFile("@billing-service/A.java"));
        assertThrows(SecurityException.class,
                () -> fileEditorService.moveFile("@billing-service/A.java", "local.java"));
        assertThrows(SecurityException.class,
                () -> fileEditorService.moveFile("copied/copy.txt", "@billing-service/A.java"));
        assertThrows(SecurityException.class,
                () -> fileEditorService.copyFile("@billing-service/A.java", "local.java"));
        assertThrows(SecurityException.class,
                () -> fileEditorService.copyFile("copied/copy.txt", "@billing-service/A.java"));
        assertThrows(SecurityException.class, () -> fileEditorService.mkdirs("@billing-service/newdir"));
    }

    @Test
    void testReplaceBlocksDisjointEditsAtomicRollbackAndOverlap() throws Exception {
        String initial = "public class Calc {\n    int add(int a, int b) { return a + b; }\n    int sub(int a, int b) { return a   -   b; }\n}\n";
        Path file = Files.writeString(tempDir.resolve("Calc.java"), initial);
        clearInvocations(eventPublisher);

        JexlServiceRegistry registry = new JexlServiceRegistry(null, null);
        registry.register(fileEditorService);
        JexlTool jexlTool = new JexlTool(registry, null, AiConfig.SHARED_OBJECT_MAPPER, null);
        ToolContext codeContext = new ToolContext(new TurnToolContext("conv-1", WorkflowPhase.CODE, null).toMap());

        String arrayScript = """
                fileEditorService.replaceBlocks('Calc.java', [
                    {'target': 'return a + b;', 'replacement': 'return Math.addExact(a, b);'},
                    {'target': 'return a - b;', 'replacement': 'return Math.subtractExact(a, b);'}
                ])
                """;
        JsonNode arrayJson = AiConfig.SHARED_OBJECT_MAPPER.readTree(jexlTool.executeJexl(arrayScript, codeContext))
                .get("result");
        assertTrue(arrayJson.get("success").asBoolean());
        verify(eventPublisher, times(1)).publishEvent(new FileChangedEvent("Calc.java"));
        SwingTestUtils.assertContainsAll(Files.readString(file), "Math.addExact", "Math.subtractExact");

        String currentContent = Files.readString(file);
        EditorResult notFoundResult = fileEditorService.replaceBlocks("Calc.java",
                List.of(Map.of("target", "Math.addExact(a, b)", "replacement", "a + b"),
                        Map.of("target", "nonExistentMethod()", "replacement", "x")));
        assertEditFails(notFoundResult, "Edit #2 failed:", "TargetBlock not found");
        assertEquals(currentContent, Files.readString(file));

        Map<String, String> nullReplacementEdit = new HashMap<>();
        nullReplacementEdit.put("target", "Math.addExact(a, b)");
        nullReplacementEdit.put("replacement", null);
        assertEditFails(fileEditorService.replaceBlocks("Calc.java", List.of(nullReplacementEdit)),
                "'replacement' must not be null");
        assertEditFails(fileEditorService.replaceBlocks("Calc.java", Collections.emptyList()), "No edits provided");

        Files.writeString(tempDir.resolve("overlap.txt"), "line 1\nline 2\nline 3\nline 4\n");
        assertEditFails(
                fileEditorService.replaceBlocks("overlap.txt",
                        List.of(Map.of("target", "line 1\nline 2\nline 3", "replacement", "A"),
                                Map.of("target", "line 2\nline 3\nline 4", "replacement", "B"))),
                "Edit #2 failed:", "overlaps with Edit #1");
    }

    @Test
    void testReplaceSymbolNearestLineHintAndRevertFile() throws Exception {
        // 1. replaceSymbol: unique match without annotation replacement preserves preceding annotation
        String source = """
                public class Service {
                    // Calculates total
                    @Deprecated
                    public int compute(int a) {
                        return a + 1;
                    }

                    public int helper() {
                        return 42;
                    }
                }
                """;
        Path serviceFile = Files.writeString(tempDir.resolve("Service.java"), source);
        List<SymbolInfo> outline = List.of(
                new SymbolInfo("Service", "class", "Service.java", 1, 11, "", "public class Service {"),
                new SymbolInfo("compute", "method", "Service.java", 4, 6, "Service", "public int compute(int a) {"),
                new SymbolInfo("helper", "method", "Service.java", 8, 10, "Service", "public int helper() {"));
        when(symbolService.getFileOutline("Service.java")).thenReturn(outline);

        EditorResult res1 = fileEditorService.replaceSymbol("Service.java", "Service.compute",
                "    public int compute(int a) {\n        return a * 10;\n    }");
        assertTrue(res1.success());
        String afterFirst = Files.readString(serviceFile);
        SwingTestUtils.assertContainsAll(afterFirst, "// Calculates total", "@Deprecated", "return a * 10;",
                "public int helper()");

        // When replacement starts with an annotation/comment, preceding context lines are replaced too
        EditorResult res2 = fileEditorService.replaceSymbol("Service.java", "compute",
                "    // Updated doc\n    @Override\n    public int compute(int a) {\n        return a * 20;\n    }");
        assertTrue(res2.success());
        String afterSecond = Files.readString(serviceFile);
        assertFalse(afterSecond.contains("@Deprecated"));
        assertFalse(afterSecond.contains("// Calculates total"));
        SwingTestUtils.assertContainsAll(afterSecond, "// Updated doc", "@Override", "return a * 20;");

        // 2. replaceSymbol: 0 matches returns outline summary hint
        EditorResult missingSym = fileEditorService.replaceSymbol("Service.java", "nonExistentMethod", "void x() {}");
        assertEditFails(missingSym, "Symbol 'nonExistentMethod' not found in file: Service.java",
                "Available symbols in Service.java", "Service.compute (method L4-6)", "Service.helper (method L8-10)");

        // 3. replaceSymbol: >1 matches (overloads) returns ambiguity hint with signatures & line ranges
        when(symbolService.getFileOutline("Service.java")).thenReturn(List.of(
                new SymbolInfo("parse", "method", "Service.java", 10, 14, "Service", "public void parse(String s) {"),
                new SymbolInfo("parse", "method", "Service.java", 16, 22, "Service",
                        "public void parse(String s, int radix) {")));
        EditorResult ambiguousSym = fileEditorService.replaceSymbol("Service.java", "parse", "public void parse() {}");
        assertEditFails(ambiguousSym, "Symbol 'parse' is ambiguous in file: Service.java (found 2 matches)",
                "Use replaceBlock or replaceLines", "L10-14 public void parse(String s) {",
                "L16-22 public void parse(String s, int radix) {");

        // 4. Nearest-line snippet in buildTargetNotFoundHint when replaceBlock fails
        EditorResult nearMiss = fileEditorService.replaceBlock("Service.java",
                "    public int helper() {\n        return 999;\n    }", "    public int helper() { return 0; }");
        assertEditFails(nearMiss, "TargetBlock not found in file: Service.java",
                "Nearest matching lines in Service.java", "public int helper()", "return 42;");

        // 5. revertFile: untracked file deleted vs tracked file restored from HEAD
        Path untrackedFile = Files.writeString(tempDir.resolve("Untracked.java"), "class Untracked {}");
        clearInvocations(eventPublisher);
        EditorResult revertUntracked = fileEditorService.revertFile("Untracked.java");
        assertTrue(revertUntracked.success());
        assertFalse(Files.exists(untrackedFile));
        verify(eventPublisher)
                .publishEvent(new FileChangedEvent("Untracked.java", FileChangedEvent.ChangeType.DELETED));

        assertEditFails(fileEditorService.revertFile("DoesNotExist.java"),
                "File does not exist on disk or in git HEAD: DoesNotExist.java");

        // Initialize a real git repo in tempDir to test tracked file revert
        org.roxycode.app.service.ProcessRunner.run(List.of("git", "init"), tempDir, true);
        org.roxycode.app.service.ProcessRunner.run(List.of("git", "config", "user.email", "test@example.com"), tempDir,
                true);
        org.roxycode.app.service.ProcessRunner.run(List.of("git", "config", "user.name", "Test User"), tempDir, true);
        Path trackedFile = Files.writeString(tempDir.resolve("Tracked.txt"), "original head content\n");
        org.roxycode.app.service.ProcessRunner.run(List.of("git", "add", "Tracked.txt"), tempDir, true);
        org.roxycode.app.service.ProcessRunner.run(List.of("git", "commit", "-m", "initial"), tempDir, true);

        Files.writeString(trackedFile, "dirty modified content\n");
        clearInvocations(eventPublisher);
        EditorResult revertTracked = fileEditorService.revertFile("Tracked.txt");
        assertTrue(revertTracked.success());
        assertEquals("original head content\n", Files.readString(trackedFile));
        verify(eventPublisher).publishEvent(new FileChangedEvent("Tracked.txt", FileChangedEvent.ChangeType.MODIFIED));
    }
}
