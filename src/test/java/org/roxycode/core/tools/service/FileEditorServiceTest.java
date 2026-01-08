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
import java.util.List;

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

        result = fileEditorService.getLines("test.txt", 1, 1);
        assertEquals("line1", result);

        result = fileEditorService.getLines("test.txt", 5, 10);
        assertEquals("line5", result);

        result = fileEditorService.getLines("test.txt", 10, 20);
        assertEquals("", result);
    }

    @Test
    void testMoveLines() throws IOException {
        Path p = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("line1", "line2", "line3", "line4", "line5");
        Files.write(p, lines, StandardCharsets.UTF_8);

        // Move line2-3 to after line5 (target index 3 because line2-3 are removed)
        // Wait, targetLine is 1-based index in the resulting list or original?
        // My implementation:
        // lines.subList(start, end).clear();
        // lines.addAll(target, toMove);
        // targetLine = 4 means it will be at index 3 in the NEW list.
        
        fileEditorService.moveLines("test.txt", 2, 3, 4);
        List<String> updatedLines = Files.readAllLines(p, StandardCharsets.UTF_8);
        assertEquals(Arrays.asList("line1", "line4", "line5", "line2", "line3"), updatedLines);
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
}
