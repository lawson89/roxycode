package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingTestUtils;
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
    void testWriteFileSuccess() throws IOException {
        assertTrue(fileEditorService.writeFile("test.txt", "Hello World").success());
        Path filePath = tempDir.resolve("test.txt");
        assertTrue(Files.exists(filePath));
        assertEquals("Hello World", Files.readString(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testReplaceBlockSuccess() throws IOException {
        String content = writeAndReplace("test.java", "public class Test {\n // TODO\n }", "// TODO", "// Done");
        assertTrue(content.contains("// Done"));
        assertFalse(content.contains("// TODO"));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
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
    void testReplaceBlockCrlfNormalization() throws IOException {
        assertEquals("class A {\r\n    int x = 10;\r\n    int y = 20;\r\n}\r\n",
                writeAndReplace("crlf_block.java", "class A {\r\n    int x = 1;\r\n    int y = 2;\r\n}\r\n",
                        "    int x = 1;\n    int y = 2;", "    int x = 10;\n    int y = 20;"));
    }

    @Test
    void testReplaceBlockWithVariableWhitespace() throws IOException {
        assertEquals("private int newMethod() { }", writeAndReplace("test.java", "public    void   myMethod() { }",
                "public void myMethod", "private int newMethod"));
    }

    @Test
    void testReplaceBlockWithNewlinesAndTabs() throws IOException {
        assertEquals("private int newMethod() { }", writeAndReplace("test_whitespace.java",
                "public\nvoid\tmyMethod() { }", "public void myMethod", "private int newMethod"));
    }

    @Test
    void testReplaceBlockNotUniqueReturnsFailureWithLineNumbers() throws IOException {
        Path filePath = Files.writeString(tempDir.resolve("test.java"), "// TODO\n // TODO");
        assertEditFails(fileEditorService.replaceBlock("test.java", "// TODO", "// Done"), "not unique", "1", "2");
        Files.writeString(filePath, "int   a = 1;\nString middle = 'ok';\nint     a   = 1;");
        assertEditFails(fileEditorService.replaceBlock("test.java", "int a = 1;", "int a = 2;"), "not unique", "1",
                "3");
    }

    @Test
    void testReplaceBlockNotFoundReturnsDiagnosticClosestLineHint() throws IOException {
        Files.writeString(tempDir.resolve("service.java"),
                "package example;\npublic class Service {\n    public String computeTotal(int count) {\n        return 'total=' + count;\n    }\n}");
        assertEditFails(
                fileEditorService.replaceBlock("service.java", "public String computeTotal(double amount)",
                        "public String computeTotal(long amount)"),
                "TargetBlock not found in file: service.java", "Line 3:     public String computeTotal(int count) {");
        assertEditFails(fileEditorService.replaceBlock("nonexistent.java", "foo", "bar"),
                "File does not exist: nonexistent.java");
    }

    @Test
    void testReplaceLinesSuccess() throws IOException {
        Path filePath = Files.write(tempDir.resolve("test.txt"), List.of("Line 1", "Line 2", "Line 3", "Line 4"));
        assertTrue(fileEditorService.replaceLines("test.txt", 2, 3, "New Content").success());
        assertEquals(List.of("Line 1", "New Content", "Line 4"), Files.readAllLines(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testReplaceLinesMultiLineAndTrailingNewline() throws IOException {
        Path filePath = Files.write(tempDir.resolve("test.txt"), List.of("Line 1", "Line 2", "Line 3", "Line 4"));
        assertTrue(fileEditorService.replaceLines("test.txt", 2, 3, "Replaced 2\nReplaced 3\n").success());
        assertEquals(List.of("Line 1", "Replaced 2", "Replaced 3", "Line 4"), Files.readAllLines(filePath));
    }

    @Test
    void testReplaceLinesAndInsertAtLineOutOfBoundsReturnStructuredErrorHints() throws IOException {
        Files.write(tempDir.resolve("bounds.txt"), List.of("Line 1", "Line 2", "Line 3"));
        assertEditFails(fileEditorService.replaceLines("bounds.txt", 2, 5, "Replacement"), "Invalid line range: 2 to 5",
                "file has 3 lines");
        assertEditFails(fileEditorService.replaceLines("missing_bounds.txt", 1, 1, "Replacement"),
                "File does not exist: missing_bounds.txt");
        assertEditFails(fileEditorService.insertAtLine("bounds.txt", 6, "Inserted"), "Invalid line number: 6",
                "valid range: 1 to 4", "file has 3 lines");
        assertEditFails(fileEditorService.insertAtLine("missing_bounds.txt", 1, "Inserted"),
                "File does not exist: missing_bounds.txt");
    }

    @Test
    void testInsertAtLineSuccess() throws IOException {
        Path filePath = Files.write(tempDir.resolve("test.txt"), List.of("Line 1", "Line 3"));
        assertTrue(fileEditorService.insertAtLine("test.txt", 2, "Line 2").success());
        assertEquals(List.of("Line 1", "Line 2", "Line 3"), Files.readAllLines(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testInsertAtLineMultiLineAndTrailingNewline() throws IOException {
        Path filePath = Files.write(tempDir.resolve("test.txt"), List.of("Line 1", "Line 4"));
        assertTrue(fileEditorService.insertAtLine("test.txt", 2, "Line 2\nLine 3\n").success());
        assertEquals(List.of("Line 1", "Line 2", "Line 3", "Line 4"), Files.readAllLines(filePath));
    }

    @Test
    void testDeleteFileSuccess() throws IOException {
        Path filePath = Files.writeString(tempDir.resolve("to_delete.txt"), "delete me");
        assertTrue(fileEditorService.deleteFile("to_delete.txt").success());
        assertFalse(Files.exists(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testDeleteFileNonExistentAndDirectoryReturnStructuredFailure() throws IOException {
        assertEditFails(fileEditorService.deleteFile("missing.txt"), "File does not exist: missing.txt");
        Files.createDirectory(tempDir.resolve("some_dir"));
        assertEditFails(fileEditorService.deleteFile("some_dir"), "Cannot delete directory: some_dir");
    }

    @Test
    void testMoveCopyAndMkdirsOperations() throws IOException {
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
    void testPathTraversalReturnsFailure() {
        assertTrue(assertThrows(SecurityException.class, () -> fileEditorService.writeFile("../outside.txt", "content"))
                .getMessage().contains("Path traversal"));
    }

    @Test
    void testWriteFileNullParentDoesNotThrowNPE() throws IOException {
        ProjectService rootMockService = mock(ProjectService.class);
        Path bareRelativePath = Path.of("target_bare_root_test_file.txt");
        assertNull(bareRelativePath.getParent(), "Single-element relative Path has null parent");
        when(rootMockService.resolvePath("target_bare_root_test_file.txt")).thenReturn(bareRelativePath);
        FileEditorService customService = new FileEditorService(rootMockService, eventPublisher);
        try {
            assertTrue(customService.writeFile("target_bare_root_test_file.txt", "root content").success());
            assertEquals("root content", Files.readString(bareRelativePath));
        } finally {
            Files.deleteIfExists(bareRelativePath);
        }
    }

    @Test
    void testReplaceLinesAndInsertAtLinePreserveCrlfAndNoTrailingNewline() throws IOException {
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
    }

    @Test
    void testReplaceLinesAndInsertAtLinePreserveExplicitTrailingBlankLines() throws IOException {
        assertEquals(List.of("a"), FileEditorService.splitLines("a"));
        assertEquals(List.of("a"), FileEditorService.splitLines("a" + (char) 10));
        assertEquals(List.of("a", ""), FileEditorService.splitLines("a" + (char) 10 + (char) 10));
        assertEquals(List.of("", ""), FileEditorService.splitLines("" + (char) 10));
        assertEquals(List.of(""), FileEditorService.splitLines(""));

        Path file = Files.write(tempDir.resolve("blank_lines.txt"), List.of("Line 1", "Line 2", "Line 3"));
        assertTrue(fileEditorService.insertAtLine("blank_lines.txt", 2, "Inserted" + (char) 10 + (char) 10).success());
        assertEquals(List.of("Line 1", "Inserted", "", "Line 2", "Line 3"), Files.readAllLines(file));

        assertTrue(fileEditorService
                .replaceLines("blank_lines.txt", 2, 3, "Replaced A" + (char) 10 + "Replaced B" + (char) 10 + (char) 10)
                .success());
        assertEquals(List.of("Line 1", "Replaced A", "Replaced B", "", "Line 2", "Line 3"), Files.readAllLines(file));
    }
}
