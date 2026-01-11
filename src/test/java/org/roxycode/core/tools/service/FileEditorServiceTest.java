package org.roxycode.core.tools.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;

class FileEditorServiceTest {

    @TempDir
    Path tempDir;

    private Sandbox sandbox;
    private FileEditorService fileEditorService;

    @BeforeEach
    void setUp() {
        sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        fileEditorService = new FileEditorService(sandbox);
    }

    @Test
    void testGetLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3", "line4", "line5");
        Files.write(p, lines, StandardCharsets.UTF_8);

        String result = fileEditorService.getLines("test.txt", 2, 4);
        assertEquals("line2\nline3\nline4", result);
    }

    @Test
    void testGetLines_OutOfBounds() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2");
        Files.write(p, lines, StandardCharsets.UTF_8);

        assertEquals("line1\nline2", fileEditorService.getLines("test.txt", -5, 10));
        assertEquals("", fileEditorService.getLines("test.txt", 5, 10));
    }

    @Test
    void testGetLines_EmptyFile() throws IOException {
        Path p = tempDir.resolve("empty.txt");
        Files.createFile(p);

        assertEquals("", fileEditorService.getLines("empty.txt", 1, 1));
    }

    @Test
    void testGetLines_NonExistentFile() {
        assertThrows(IOException.class, () -> fileEditorService.getLines("missing.txt", 1, 1));
    }

    @Test
    void testGetLinesWithNumbers() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3");
        Files.write(p, lines, StandardCharsets.UTF_8);

        String result = fileEditorService.getLinesWithNumbers("test.txt", 1, 3);
        assertEquals("1: line1\n2: line2\n3: line3", result);
    }


    @Test
    void testReadFile() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3");
        Files.write(p, lines, StandardCharsets.UTF_8);

        String result = fileEditorService.readFile("test.txt");
        assertEquals("line1\nline2\nline3", result);
    }

    @Test
    void testReadFileWithNumbers() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2");
        Files.write(p, lines, StandardCharsets.UTF_8);

        String result = fileEditorService.readFileWithNumbers("test.txt");
        assertEquals("1: line1\n2: line2", result);
    }
    @Test
    void testFindLine() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("public class Test {", "    void test() {", "    }", "}");
        Files.write(p, lines, StandardCharsets.UTF_8);

        assertEquals(2, fileEditorService.findLine("test.txt", "void", 1));
        assertEquals(3, fileEditorService.findLine("test.txt", "}", 3));
        assertEquals(-1, fileEditorService.findLine("test.txt", "missing", 1));
    }

    @Test
    void testFindLine_InvalidRegex() throws IOException {
        Path p = tempDir.resolve("test.txt");
        Files.write(p, Collections.singletonList("some content"));

        assertThrows(PatternSyntaxException.class, () -> fileEditorService.findLine("test.txt", "[", 1));
    }

    @Test
    void testMoveLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3", "line4", "line5");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.moveLines("test.txt", 2, 3, 4);
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line1", "line4", "line5", "line2", "line3"), updatedLines);
    }

    @Test
    void testMoveLines_OutOfBounds() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3");
        Files.write(p, lines, StandardCharsets.UTF_8);

        // Move beyond end
        fileEditorService.moveLines("test.txt", 1, 1, 10);
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line2", "line3", "line1"), updatedLines);
    }

    @Test
    void testDeleteLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3", "line4", "line5");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.deleteLines("test.txt", 2, 4);
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line1", "line5"), updatedLines);
    }

    @Test
    void testReplaceLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3", "line4", "line5");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.replaceLines("test.txt", 2, 4, Arrays.asList("new2", "new3"));
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line1", "new2", "new3", "line5"), updatedLines);
    }

    @Test
    void testReplaceLines_Append() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.replaceLines("test.txt", 3, 3, Collections.singletonList("line3"));
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line1", "line2", "line3"), updatedLines);
    }

    @Test
    void testInsertLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.insertLines("test.txt", 2, Arrays.asList("inserted"));
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line1", "inserted", "line2"), updatedLines);
    }

    @Test
    void testIndentLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "  line2", "    line3");
        Files.write(p, lines, StandardCharsets.UTF_8);

        // Add 2 spaces
        fileEditorService.indentLines("test.txt", 1, 1, 2);
        // Remove 2 spaces
        fileEditorService.indentLines("test.txt", 3, 3, -2);

        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("  line1", "  line2", "  line3"), updatedLines);
    }

    @Test
    void testReplaceInLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("apple", "banana", "apple pie");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.replaceInLines("test.txt", "apple", "orange", 1, 2);
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("orange", "banana", "apple pie"), updatedLines);
    }

    @Test
    void testReplaceInLines_InvalidRegex() throws IOException {
        Path p = tempDir.resolve("test.txt");
        Files.write(p, Collections.singletonList("content"));

        assertThrows(PatternSyntaxException.class, () -> fileEditorService.replaceInLines("test.txt", "[", "replacement", 1, 1));
    }

    @Test
    void testUndo() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2");
        Files.write(p, lines, StandardCharsets.UTF_8);

        fileEditorService.deleteLines("test.txt", 1, 1);
        assertEquals(Arrays.asList("line2"), Files.readAllLines(p, StandardCharsets.UTF_8));

        fileEditorService.undo("test.txt");
        assertEquals(Arrays.asList("line1", "line2"), Files.readAllLines(p, StandardCharsets.UTF_8));
    }
}
