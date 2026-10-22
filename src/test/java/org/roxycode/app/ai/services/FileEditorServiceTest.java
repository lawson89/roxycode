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
import org.roxycode.app.ai.AgentJexlPermissions;
import org.roxycode.app.ai.LLMDocGenerator;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.springframework.context.ApplicationEventPublisher;

class FileEditorServiceTest {

    @TempDir Path tempDir;

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

    @Test
    void testWriteFileSuccess() throws IOException {
        String relativePath = "test.txt";
        String content = "Hello World";

        EditorResult result = fileEditorService.writeFile(relativePath, content);

        assertTrue(result.success());
        Path filePath = tempDir.resolve(relativePath);
        assertTrue(Files.exists(filePath));
        assertEquals(content, Files.readString(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testReplaceBlockSuccess() throws IOException {
        Path filePath = tempDir.resolve("test.java");
        Files.writeString(
                filePath,
                """
                public class Test {
                 // TODO
                 }""");

        EditorResult result = fileEditorService.replaceBlock("test.java", "// TODO", "// Done");

        assertTrue(result.success());
        String content = Files.readString(filePath);
        assertTrue(content.contains("// Done"));
        assertFalse(content.contains("// TODO"));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testReplaceBlockPythonAndYamlIndentationNeverDoubledOnExactAndFallbackMatch()
            throws IOException {
        Path pyFile = tempDir.resolve("script.py");
        String initialPy =
                """
                def calculate(x):
                    if x == 1:
                        return 10
                    return 0
                """;
        Files.writeString(pyFile, initialPy);

        // Pass 1: Exact match where targetBlock and replacementBlock include leading 4-space indentation
        EditorResult exactResult =
                fileEditorService.replaceBlock(
                        "script.py",
                        "    if x == 1:\n        return 10",
                        "    if x == 1:\n        return 100");
        assertTrue(exactResult.success());
        assertEquals(
                """
                def calculate(x):
                    if x == 1:
                        return 100
                    return 0
                """,
                Files.readString(pyFile));

        // Pass 2: Whitespace-fuzzy fallback (file has extra spaces on line 2, so Pass 1 misses)
        Files.writeString(
                pyFile,
                """
                def calculate(x):
                    if x   ==   1:
                        return 10
                    return 0
                """);

        EditorResult fallbackResult =
                fileEditorService.replaceBlock(
                        "script.py",
                        "    if x == 1:\n        return 10",
                        "    if x == 2:\n        return 20");
        assertTrue(fallbackResult.success());
        assertEquals(
                """
                def calculate(x):
                    if x == 2:
                        return 20
                    return 0
                """,
                Files.readString(pyFile));

        // YAML test: Pass 2 fallback where targetBlock has no leading spaces but replacementBlock does
        Path yamlFile = tempDir.resolve("config.yaml");
        Files.writeString(
                yamlFile,
                """
                server:
                  host:   localhost
                  port: 8080
                """);

        EditorResult yamlResult =
                fileEditorService.replaceBlock(
                        "config.yaml",
                        "host: localhost\n  port: 8080",
                        "  host: 0.0.0.0\n  port: 9090");
        assertTrue(yamlResult.success());
        assertEquals(
                """
                server:
                  host: 0.0.0.0
                  port: 9090
                """,
                Files.readString(yamlFile));
    }

    @Test
    void testReplaceBlockCrlfNormalization() throws IOException {
        Path crlfFile = tempDir.resolve("crlf_block.java");
        Files.writeString(crlfFile, "class A {\r\n    int x = 1;\r\n    int y = 2;\r\n}\r\n");

        EditorResult result =
                fileEditorService.replaceBlock(
                        "crlf_block.java",
                        "    int x = 1;\n    int y = 2;",
                        "    int x = 10;\n    int y = 20;");

        assertTrue(result.success());
        assertEquals(
                "class A {\r\n    int x = 10;\r\n    int y = 20;\r\n}\r\n",
                Files.readString(crlfFile));
    }

    @Test
    void testReplaceBlockWithVariableWhitespace() throws IOException {
        Path filePath = tempDir.resolve("test.java");
        Files.writeString(filePath, "public    void   myMethod() { }");

        // Should match despite different whitespace in target block
        EditorResult result =
                fileEditorService.replaceBlock(
                        "test.java", "public void myMethod", "private int newMethod");

        assertTrue(result.success());
        String content = Files.readString(filePath);
        assertEquals("private int newMethod() { }", content);
    }

    @Test
    void testReplaceBlockWithNewlinesAndTabs() throws IOException {
        Path filePath = tempDir.resolve("test_whitespace.java");
        Files.writeString(
                filePath,
                """
                public
                void	myMethod() { }""");

        // Should match despite newlines and tabs
        EditorResult result =
                fileEditorService.replaceBlock(
                        "test_whitespace.java", "public void myMethod", "private int newMethod");

        assertTrue(result.success());
        String content = Files.readString(filePath);
        assertEquals("private int newMethod() { }", content);
    }

    @Test
    void testReplaceBlockNotUniqueReturnsFailureWithLineNumbers() throws IOException {
        Path filePath = tempDir.resolve("test.java");
        Files.writeString(
                filePath,
                """
                // TODO
                 // TODO""");

        EditorResult exactAmbiguous =
                fileEditorService.replaceBlock("test.java", "// TODO", "// Done");
        assertFalse(exactAmbiguous.success());
        assertNotNull(exactAmbiguous.errorHint());
        assertTrue(exactAmbiguous.errorHint().contains("not unique"));
        assertTrue(exactAmbiguous.errorHint().contains("1"));
        assertTrue(exactAmbiguous.errorHint().contains("2"));

        // Pass 2 ambiguous match
        Files.writeString(
                filePath,
                """
                int   a = 1;
                String middle = "ok";
                int     a   = 1;""");
        EditorResult regexAmbiguous =
                fileEditorService.replaceBlock("test.java", "int a = 1;", "int a = 2;");
        assertFalse(regexAmbiguous.success());
        assertTrue(regexAmbiguous.errorHint().contains("not unique"));
        assertTrue(regexAmbiguous.errorHint().contains("1"));
        assertTrue(regexAmbiguous.errorHint().contains("3"));
    }

    @Test
    void testReplaceBlockNotFoundReturnsDiagnosticClosestLineHint() throws IOException {
        Path filePath = tempDir.resolve("service.java");
        Files.writeString(
                filePath,
                """
                package example;
                public class Service {
                    public String computeTotal(int count) {
                        return "total=" + count;
                    }
                }""");

        EditorResult notFound =
                fileEditorService.replaceBlock(
                        "service.java",
                        "public String computeTotal(double amount)",
                        "public String computeTotal(long amount)");
        assertFalse(notFound.success());
        assertNotNull(notFound.errorHint());
        assertTrue(notFound.errorHint().contains("TargetBlock not found in file: service.java"));
        assertTrue(notFound.errorHint().contains("Line 3:     public String computeTotal(int count) {"));

        EditorResult missingFile =
                fileEditorService.replaceBlock("nonexistent.java", "foo", "bar");
        assertFalse(missingFile.success());
        assertEquals("File does not exist: nonexistent.java", missingFile.errorHint());
    }

    @Test
    void testReplaceLinesSuccess() throws IOException {
        Path filePath = tempDir.resolve("test.txt");
        Files.write(filePath, List.of("Line 1", "Line 2", "Line 3", "Line 4"));

        EditorResult result = fileEditorService.replaceLines("test.txt", 2, 3, "New Content");

        assertTrue(result.success());
        List<String> lines = Files.readAllLines(filePath);
        assertEquals(3, lines.size());
        assertEquals("Line 1", lines.get(0));
        assertEquals("New Content", lines.get(1));
        assertEquals("Line 4", lines.get(2));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testReplaceLinesMultiLineAndTrailingNewline() throws IOException {
        Path filePath = tempDir.resolve("test.txt");
        Files.write(filePath, List.of("Line 1", "Line 2", "Line 3", "Line 4"));

        EditorResult result =
                fileEditorService.replaceLines(
                        "test.txt",
                        2,
                        3,
                        """
                        Replaced 2
                        Replaced 3
                        """);

        assertTrue(result.success());
        List<String> lines = Files.readAllLines(filePath);
        assertEquals(List.of("Line 1", "Replaced 2", "Replaced 3", "Line 4"), lines);
    }

    @Test
    void testReplaceLinesAndInsertAtLineOutOfBoundsReturnStructuredErrorHints()
            throws IOException {
        Path filePath = tempDir.resolve("bounds.txt");
        Files.write(filePath, List.of("Line 1", "Line 2", "Line 3"));

        EditorResult badRange = fileEditorService.replaceLines("bounds.txt", 2, 5, "Replacement");
        assertFalse(badRange.success());
        assertTrue(badRange.errorHint().contains("Invalid line range: 2 to 5"));
        assertTrue(badRange.errorHint().contains("file has 3 lines"));

        EditorResult missingReplace =
                fileEditorService.replaceLines("missing_bounds.txt", 1, 1, "Replacement");
        assertFalse(missingReplace.success());
        assertEquals("File does not exist: missing_bounds.txt", missingReplace.errorHint());

        EditorResult badInsert = fileEditorService.insertAtLine("bounds.txt", 6, "Inserted");
        assertFalse(badInsert.success());
        assertTrue(badInsert.errorHint().contains("Invalid line number: 6"));
        assertTrue(badInsert.errorHint().contains("valid range: 1 to 4"));
        assertTrue(badInsert.errorHint().contains("file has 3 lines"));

        EditorResult missingInsert =
                fileEditorService.insertAtLine("missing_bounds.txt", 1, "Inserted");
        assertFalse(missingInsert.success());
        assertEquals("File does not exist: missing_bounds.txt", missingInsert.errorHint());
    }

    @Test
    void testInsertAtLineSuccess() throws IOException {
        Path filePath = tempDir.resolve("test.txt");
        Files.write(filePath, List.of("Line 1", "Line 3"));

        EditorResult result = fileEditorService.insertAtLine("test.txt", 2, "Line 2");

        assertTrue(result.success());
        List<String> lines = Files.readAllLines(filePath);
        assertEquals(3, lines.size());
        assertEquals("Line 2", lines.get(1));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testInsertAtLineMultiLineAndTrailingNewline() throws IOException {
        Path filePath = tempDir.resolve("test.txt");
        Files.write(filePath, List.of("Line 1", "Line 4"));

        EditorResult result =
                fileEditorService.insertAtLine(
                        "test.txt",
                        2,
                        """
                        Line 2
                        Line 3
                        """);

        assertTrue(result.success());
        List<String> lines = Files.readAllLines(filePath);
        assertEquals(List.of("Line 1", "Line 2", "Line 3", "Line 4"), lines);
    }

    @Test
    void testDeleteFileSuccess() throws IOException {
        Path filePath = tempDir.resolve("to_delete.txt");
        Files.writeString(filePath, "delete me");

        EditorResult result = fileEditorService.deleteFile("to_delete.txt");

        assertTrue(result.success());
        assertFalse(Files.exists(filePath));
        verify(eventPublisher, atLeastOnce()).publishEvent(isA(FileChangedEvent.class));
    }

    @Test
    void testDeleteFileNonExistentAndDirectoryReturnStructuredFailure() throws IOException {
        EditorResult missingResult = fileEditorService.deleteFile("missing.txt");
        assertFalse(missingResult.success());
        assertEquals("File does not exist: missing.txt", missingResult.errorHint());

        Files.createDirectory(tempDir.resolve("some_dir"));
        EditorResult dirResult = fileEditorService.deleteFile("some_dir");
        assertFalse(dirResult.success());
        assertTrue(dirResult.errorHint().contains("Cannot delete directory: some_dir"));
    }

    @Test
    void testMoveCopyAndMkdirsOperations() throws IOException {
        // mkdirs success & conflict with existing file
        EditorResult mkdirResult = fileEditorService.mkdirs("nested/dir/structure");
        assertTrue(mkdirResult.success());
        assertTrue(Files.isDirectory(tempDir.resolve("nested/dir/structure")));

        Path existingFile = tempDir.resolve("existing.txt");
        Files.writeString(existingFile, "hello");
        EditorResult mkdirConflict = fileEditorService.mkdirs("existing.txt");
        assertFalse(mkdirConflict.success());
        assertTrue(mkdirConflict.errorHint().contains("existing.txt"));

        // copyFile success, parent creation, missing source, and directory source
        EditorResult copySuccess =
                fileEditorService.copyFile("existing.txt", "copied/sub/existing_copy.txt");
        assertTrue(copySuccess.success());
        assertEquals("hello", Files.readString(tempDir.resolve("copied/sub/existing_copy.txt")));
        assertTrue(Files.exists(existingFile));

        EditorResult copyMissing = fileEditorService.copyFile("nope.txt", "copied/nope.txt");
        assertFalse(copyMissing.success());
        assertEquals("File does not exist: nope.txt", copyMissing.errorHint());

        EditorResult copyDir = fileEditorService.copyFile("nested", "copied/nested_copy");
        assertFalse(copyDir.success());
        assertTrue(copyDir.errorHint().contains("nested"));

        // moveFile success, parent creation, and missing source
        EditorResult moveSuccess =
                fileEditorService.moveFile("existing.txt", "moved/sub/renamed.txt");
        assertTrue(moveSuccess.success());
        assertFalse(Files.exists(existingFile));
        assertEquals("hello", Files.readString(tempDir.resolve("moved/sub/renamed.txt")));

        EditorResult moveMissing = fileEditorService.moveFile("existing.txt", "moved/again.txt");
        assertFalse(moveMissing.success());
        assertEquals("File does not exist: existing.txt", moveMissing.errorHint());

        // Path traversal protection on new operations
        assertThrows(
                SecurityException.class,
                () -> fileEditorService.moveFile("../outside.txt", "inside.txt"));
        assertThrows(
                SecurityException.class,
                () -> fileEditorService.copyFile("moved/sub/renamed.txt", "../outside.txt"));
        assertThrows(SecurityException.class, () -> fileEditorService.mkdirs("../outside_dir"));
    }

    @Test
    void testNewMethodsExposedInJexlPermissionsAndLlmDocGenerator() throws Exception {
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        FileEditorService.class,
                        FileEditorService.class.getMethod("moveFile", String.class, String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        FileEditorService.class,
                        FileEditorService.class.getMethod("copyFile", String.class, String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        FileEditorService.class,
                        FileEditorService.class.getMethod("mkdirs", String.class)));

        String doc = new LLMDocGenerator().generateDoc(List.of(fileEditorService));
        assertTrue(doc.contains("moveFile(sourcePath: string, targetPath: string): EditorResult;"));
        assertTrue(doc.contains("copyFile(sourcePath: string, targetPath: string): EditorResult;"));
        assertTrue(doc.contains("mkdirs(relativePath: string): EditorResult;"));
    }

    @Test
    void testPathTraversalReturnsFailure() {
        SecurityException ex =
                assertThrows(
                        SecurityException.class,
                        () -> fileEditorService.writeFile("../outside.txt", "content"));
        assertTrue(ex.getMessage().contains("Path traversal"));
    }

    @Test
    void testWriteFileNullParentDoesNotThrowNPE() throws IOException {
        ProjectService rootMockService = mock(ProjectService.class);
        Path bareRelativePath = Path.of("target_bare_root_test_file.txt");
        assertNull(bareRelativePath.getParent(), "Single-element relative Path has null parent");
        when(rootMockService.resolvePath("target_bare_root_test_file.txt"))
                .thenReturn(bareRelativePath);
        FileEditorService customService = new FileEditorService(rootMockService, eventPublisher);
        try {
            EditorResult result =
                    customService.writeFile("target_bare_root_test_file.txt", "root content");
            assertTrue(result.success());
            assertEquals("root content", Files.readString(bareRelativePath));
        } finally {
            Files.deleteIfExists(bareRelativePath);
        }
    }

    @Test
    void testReplaceLinesAndInsertAtLinePreserveCrlfAndNoTrailingNewline() throws IOException {
        Path crlfFile = tempDir.resolve("crlf.txt");
        Files.writeString(crlfFile, "Line 1\r\nLine 2\r\nLine 3\r\n");

        fileEditorService.replaceLines("crlf.txt", 2, 2, "Updated 2");
        assertEquals("Line 1\r\nUpdated 2\r\nLine 3\r\n", Files.readString(crlfFile));

        fileEditorService.insertAtLine("crlf.txt", 2, "Inserted 1.5");
        assertEquals(
                "Line 1\r\nInserted 1.5\r\nUpdated 2\r\nLine 3\r\n", Files.readString(crlfFile));

        Path noTrailingFile = tempDir.resolve("no_trailing.txt");
        Files.writeString(noTrailingFile, "Alpha\nBeta\nGamma");

        fileEditorService.replaceLines("no_trailing.txt", 2, 2, "BetaUpdated");
        assertEquals("Alpha\nBetaUpdated\nGamma", Files.readString(noTrailingFile));

        fileEditorService.insertAtLine("no_trailing.txt", 3, "BeforeGamma");
        assertEquals("Alpha\nBetaUpdated\nBeforeGamma\nGamma", Files.readString(noTrailingFile));
    }
}
