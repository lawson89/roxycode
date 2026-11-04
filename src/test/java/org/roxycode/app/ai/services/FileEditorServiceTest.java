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
}
