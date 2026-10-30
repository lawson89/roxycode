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
    private FileEditorService fileEditorService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        fileEditorService = new FileEditorService(projectService, eventPublisher);
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
    void testReplaceBlockPythonAndYamlIndentationNeverDoubledOnExactAndFallbackMatch() throws IOException {
        assertEquals("def calculate(x):\n    if x == 1:\n        return 100\n    return 0\n",
                writeAndReplace("script.py", "def calculate(x):\n    if x == 1:\n        return 10\n    return 0\n",
                        "    if x == 1:\n        return 10", "    if x == 1:\n        return 100"));
        assertEquals("def calculate(x):\n    if x == 2:\n        return 20\n    return 0\n",
                writeAndReplace("script.py", "def calculate(x):\n    if x   ==   1:\n        return 10\n    return 0\n",
                        "    if x == 1:\n        return 10", "    if x == 2:\n        return 20"));
        assertEquals("server:\n  host: 0.0.0.0\n  port: 9090\n",
                writeAndReplace("config.yaml", "server:\n  host:   localhost\n  port: 8080\n",
                        "host: localhost\n  port: 8080", "  host: 0.0.0.0\n  port: 9090"));
    }

    @Test
    void testReplaceBlockMatchingCrlfAndDiagnostics() throws IOException {
        assertEquals("class A {\r\n    int x = 10;\r\n    int y = 20;\r\n}\r\n",
                writeAndReplace("crlf_block.java", "class A {\r\n    int x = 1;\r\n    int y = 2;\r\n}\r\n",
                        "    int x = 1;\n    int y = 2;", "    int x = 10;\n    int y = 20;"));

        Path filePath = Files.writeString(tempDir.resolve("test.java"), "// TODO\n // TODO");
        assertEditFails(fileEditorService.replaceBlock("test.java", "// TODO", "// Done"), "not unique", "1", "2");
        Files.writeString(filePath, "int   a = 1;\nString middle = 'ok';\nint     a   = 1;");
        assertEditFails(fileEditorService.replaceBlock("test.java", "int a = 1;", "int a = 2;"), "not unique", "1",
                "3");

        Files.writeString(tempDir.resolve("service.java"),
                "package example;\npublic class Service {\n    public String computeTotal(int count) {\n        return 'total=' + count;\n    }\n}");
        assertEditFails(
                fileEditorService.replaceBlock("service.java", "public String computeTotal(double amount)",
                        "public String computeTotal(long amount)"),
                "TargetBlock not found in file: service.java (file has 6 lines)");
        assertEditFails(fileEditorService.replaceBlock("nonexistent.java", "foo", "bar"),
                "File does not exist: nonexistent.java");
    }

    @Test
    void testReplaceLinesAndInsertAtLineFormattingAndBounds() throws IOException {
        Path filePath = Files.write(tempDir.resolve("test.txt"), List.of("Line 1", "Line 2", "Line 3", "Line 4"));
        assertTrue(fileEditorService.replaceLines("test.txt", 2, 3, "New Content").success());
        assertEquals(List.of("Line 1", "New Content", "Line 4"), Files.readAllLines(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));

        assertTrue(fileEditorService.replaceLines("test.txt", 2, 2, "Replaced 2\nReplaced 3\n").success());
        assertEquals(List.of("Line 1", "Replaced 2", "Replaced 3", "Line 4"), Files.readAllLines(filePath));

        Files.write(tempDir.resolve("bounds.txt"), List.of("Line 1", "Line 2", "Line 3"));
        assertEditFails(fileEditorService.replaceLines("bounds.txt", 2, 5, "Replacement"), "Invalid line range: 2 to 5",
                "file has 3 lines");
        assertEditFails(fileEditorService.replaceLines("missing_bounds.txt", 1, 1, "Replacement"),
                "File does not exist: missing_bounds.txt");
        assertEditFails(fileEditorService.insertAtLine("bounds.txt", 6, "Inserted"), "Invalid line number: 6",
                "valid range: 1 to 4", "file has 3 lines");
        assertEditFails(fileEditorService.insertAtLine("missing_bounds.txt", 1, "Inserted"),
                "File does not exist: missing_bounds.txt");

        Path crlfFile = Files.writeString(tempDir.resolve("crlf.txt"), "Line 1\r\nLine 2\r\nLine 3\r\n");
        fileEditorService.replaceLines("crlf.txt", 2, 2, "Updated 2");
        assertEquals("Line 1\r\nUpdated 2\r\nLine 3\r\n", Files.readString(crlfFile));

        fileEditorService.insertAtLine("crlf.txt", 2, "Inserted 1.5");
        assertEquals("Line 1\r\nInserted 1.5\r\nUpdated 2\r\nLine 3\r\n", Files.readString(crlfFile));

        Path noTrailingFile = Files.writeString(tempDir.resolve("no_trailing.txt"), "Alpha\nBeta\nGamma");
        fileEditorService.replaceLines("no_trailing.txt", 2, 2, "BetaUpdated");
        assertEquals("Alpha\nBetaUpdated\nGamma", Files.readString(noTrailingFile));

        fileEditorService.insertAtLine("no_trailing.txt", 3, "BeforeGamma");
        assertEquals("Alpha" + (char) 10 + "BetaUpdated" + (char) 10 + "BeforeGamma" + (char) 10 + "Gamma",
                Files.readString(noTrailingFile));

        assertEquals(List.of("a"), FileEditorService.splitLines("a"));
        assertEquals(List.of("a"), FileEditorService.splitLines("a" + (char) 10));
        assertEquals(List.of("a", ""), FileEditorService.splitLines("a" + (char) 10 + (char) 10));
        assertEquals(List.of("", ""), FileEditorService.splitLines("" + (char) 10));
        assertEquals(List.of(""), FileEditorService.splitLines(""));

        Path blankFile = Files.write(tempDir.resolve("blank_lines.txt"), List.of("Line 1", "Line 2", "Line 3"));
        assertTrue(fileEditorService.insertAtLine("blank_lines.txt", 2, "Inserted" + (char) 10 + (char) 10).success());
        assertEquals(List.of("Line 1", "Inserted", "", "Line 2", "Line 3"), Files.readAllLines(blankFile));

        assertTrue(fileEditorService
                .replaceLines("blank_lines.txt", 2, 3, "Replaced A" + (char) 10 + "Replaced B" + (char) 10 + (char) 10)
                .success());
        assertEquals(List.of("Line 1", "Replaced A", "Replaced B", "", "Line 2", "Line 3"),
                Files.readAllLines(blankFile));
    }

    @Test
    void testMoveCopyDeleteAndMkdirsOperations() throws IOException {
        Path toDelete = Files.writeString(tempDir.resolve("to_delete.txt"), "delete me");
        assertTrue(fileEditorService.deleteFile("to_delete.txt").success());
        assertFalse(Files.exists(toDelete));
        assertEditFails(fileEditorService.deleteFile("missing.txt"), "File does not exist: missing.txt");
        Files.createDirectory(tempDir.resolve("some_dir"));
        assertEditFails(fileEditorService.deleteFile("some_dir"), "Cannot delete directory: some_dir");

        assertTrue(fileEditorService.mkdirs("nested/dir/structure").success());
        assertTrue(Files.isDirectory(tempDir.resolve("nested/dir/structure")));

        Path existingFile = Files.writeString(tempDir.resolve("existing.txt"), "hello");
        assertEditFails(fileEditorService.mkdirs("existing.txt"), "existing.txt");

        assertTrue(fileEditorService.copyFile("existing.txt", "copied/sub/existing_copy.txt").success());
        assertEquals("hello", Files.readString(tempDir.resolve("copied/sub/existing_copy.txt")));
        assertTrue(Files.exists(existingFile));

        assertEditFails(fileEditorService.copyFile("nope.txt", "copied/nope.txt"), "File does not exist: nope.txt");
        assertEditFails(fileEditorService.copyFile("nested", "copied/nested_copy"), "nested");

        clearInvocations(eventPublisher);
        assertTrue(fileEditorService.moveFile("existing.txt", "moved/sub/renamed.txt").success());
        assertFalse(Files.exists(existingFile));
        assertEquals("hello", Files.readString(tempDir.resolve("moved/sub/renamed.txt")));
        verify(eventPublisher).publishEvent(new FileChangedEvent("existing.txt"));
        verify(eventPublisher).publishEvent(new FileChangedEvent("moved/sub/renamed.txt"));

        assertEditFails(fileEditorService.moveFile("existing.txt", "moved/again.txt"),
                "File does not exist: existing.txt");
        assertThrows(SecurityException.class, () -> fileEditorService.moveFile("../outside.txt", "inside.txt"));
        assertThrows(SecurityException.class,
                () -> fileEditorService.copyFile("moved/sub/renamed.txt", "../outside.txt"));
        assertThrows(SecurityException.class, () -> fileEditorService.mkdirs("../outside_dir"));
    }

    @Test
    void testReplaceBlocksDisjointEditsViaJexlListAndArraySingleWrite() throws Exception {
        String initial = """
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int subtract(int a, int b) {
                        return a   -   b;
                    }

                    public int multiply(int a, int b) {
                        return a * b;
                    }
                }
                """;
        Path file = Files.writeString(tempDir.resolve("Calculator.java"), initial);
        clearInvocations(eventPublisher);

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(fileEditorService);
        JexlTool jexlTool = new JexlTool(registry, null, AiConfig.SHARED_OBJECT_MAPPER, null);
        ToolContext codeContext = new ToolContext(new TurnToolContext("conv-1", WorkflowPhase.CODE, null).toMap());

        String arrayScript = """
                fileEditorService.replaceBlocks('Calculator.java', [
                    {'target': 'return a + b;', 'replacement': 'return Math.addExact(a, b);'},
                    {'target': 'return a - b;', 'replacement': 'return Math.subtractExact(a, b);'},
                    {'target': 'return a * b;', 'replacement': 'return Math.multiplyExact(a, b);'}
                ])
                """;
        JsonNode arrayJson = AiConfig.SHARED_OBJECT_MAPPER.readTree(jexlTool.executeJexl(arrayScript, codeContext))
                .get("result");
        assertTrue(arrayJson.get("success").asBoolean());
        verify(eventPublisher, times(1)).publishEvent(new FileChangedEvent("Calculator.java"));

        String afterArrayEdits = Files.readString(file);
        SwingTestUtils.assertContainsAll(afterArrayEdits, "return Math.addExact(a, b);",
                "return Math.subtractExact(a, b);", "return Math.multiplyExact(a, b);");

        clearInvocations(eventPublisher);
        String listScript = """
                fileEditorService.replaceBlocks('Calculator.java', [
                    {'target': 'Math.multiplyExact(a, b)', 'replacement': 'a * b'},
                    {'target': 'Math.addExact(a, b)', 'replacement': 'a + b'},
                    ...
                ])
                """;
        JsonNode listJson = AiConfig.SHARED_OBJECT_MAPPER.readTree(jexlTool.executeJexl(listScript, codeContext))
                .get("result");
        assertTrue(listJson.get("success").asBoolean());
        verify(eventPublisher, times(1)).publishEvent(new FileChangedEvent("Calculator.java"));
        SwingTestUtils.assertContainsAll(Files.readString(file), "return a + b;", "return Math.subtractExact(a, b);",
                "return a * b;");
    }

    @Test
    void testReplaceBlocksAtomicRollbackOverlapAndLineEndings() throws IOException {
        String initial = """
                package demo;
                public class OrderService {
                    public String createOrder(int id) {
                        return 'created:' + id;
                    }
                    public String cancelOrder(int id) {
                        return 'cancelled:' + id;
                    }
                }
                """;
        Path file = Files.writeString(tempDir.resolve("OrderService.java"), initial);
        clearInvocations(eventPublisher);

        EditorResult notFoundResult = fileEditorService.replaceBlocks("OrderService.java",
                List.of(Map.of("target", "return 'created:' + id;", "replacement", "return 'ok:' + id;"),
                        Map.of("target", "public String cancelOrder(String orderCode)", "replacement",
                                "public String cancelOrder(long id)")));
        assertEditFails(notFoundResult, "Edit #2 failed:",
                "TargetBlock not found in file: OrderService.java (file has 9 lines)");
        assertEquals(initial, Files.readString(file));
        verify(eventPublisher, never()).publishEvent(any());

        String duplicateFileContent = """
                int x = 1;
                int y = 2;
                int y = 2;
                """;
        Path dupFile = Files.writeString(tempDir.resolve("Dup.java"), duplicateFileContent);
        EditorResult nonUniqueResult = fileEditorService.replaceBlocks("Dup.java",
                new Object[]{Map.of("target", "int x = 1;", "replacement", "int x = 10;"),
                        Map.of("target", "int y = 2;", "replacement", "int y = 20;")});
        assertEditFails(nonUniqueResult, "Edit #2 failed:", "TargetBlock is not unique in file: Dup.java", "[2, 3]");
        assertEquals(duplicateFileContent, Files.readString(dupFile));

        Map<String, String> nullReplacementEdit = new HashMap<>();
        nullReplacementEdit.put("target", "return 'cancelled:' + id;");
        nullReplacementEdit.put("replacement", null);
        assertEditFails(fileEditorService.replaceBlocks("OrderService.java", List
                .of(Map.of("target", "return 'created:' + id;", "replacement", "return 'ok';"), nullReplacementEdit)),
                "Edit #2 failed:", "'replacement' must not be null");
        assertEquals(initial, Files.readString(file));

        assertEditFails(fileEditorService.replaceBlocks("OrderService.java", new Object[]{"not-a-map"}),
                "Edit #1 failed:", "Edit entry must be a map");
        assertEditFails(fileEditorService.replaceBlocks("OrderService.java", Collections.emptyList()),
                "No edits provided for file: OrderService.java");
        assertEditFails(fileEditorService.replaceBlocks("OrderService.java", (Object[]) null),
                "No edits provided for file: OrderService.java");
        assertEditFails(fileEditorService.replaceBlocks("missing_multi.java",
                List.of(Map.of("target", "a", "replacement", "b"))), "File does not exist: missing_multi.java");

        String overlapInitial = "line 1\nline 2\nline 3\nline 4\n";
        Path overlapFile = Files.writeString(tempDir.resolve("overlap.txt"), overlapInitial);
        EditorResult overlapRes = fileEditorService.replaceBlocks("overlap.txt",
                List.of(Map.of("target", "line 1\nline 2\nline 3", "replacement", "block A"),
                        Map.of("target", "line 2\nline 3\nline 4", "replacement", "block B")));
        assertEditFails(overlapRes, "Edit #2 failed:", "Target block overlaps with Edit #1 in file: overlap.txt");
        assertEquals(overlapInitial, Files.readString(overlapFile));
        verify(eventPublisher, never()).publishEvent(any());

        String crlf = String.valueOf((char) 13) + (char) 10;
        String lf = String.valueOf((char) 10);
        Path crlfFile = Files.writeString(tempDir.resolve("crlf_multi.java"),
                String.join(crlf, "class A {", "    int a = 1;", "    int b = 2;", "    int c = 3;", "}") + crlf);
        EditorResult crlfResult = fileEditorService.replaceBlocks("crlf_multi.java",
                List.of(Map.of("target", "    int a = 1;" + lf + "    int b = 2;", "replacement",
                        "    int a = 10;" + lf + "    int b = 20;"),
                        Map.of("target", "    int c = 3;", "replacement", "    int c = 30;" + lf + "    int d = 40;")));
        assertTrue(crlfResult.success());
        assertEquals(String.join(crlf, "class A {", "    int a = 10;", "    int b = 20;", "    int c = 30;",
                "    int d = 40;", "}") + crlf, Files.readString(crlfFile));

        Path lfFile = Files.writeString(tempDir.resolve("lf_multi.java"),
                String.join(lf, "class B {", "    int a = 1;", "    int b = 2;", "}") + lf);
        EditorResult lfResult = fileEditorService.replaceBlocks("lf_multi.java",
                List.of(Map.of("target", "    int a = 1;" + crlf, "replacement", "    int a = 100;" + crlf),
                        Map.of("target", "    int b = 2;", "replacement", "    int b = 200;")));
        assertTrue(lfResult.success());
        assertEquals(String.join(lf, "class B {", "    int a = 100;", "    int b = 200;", "}") + lf,
                Files.readString(lfFile));
    }
}
