package org.roxycode.app.ai.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FileReadServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private FileReadService fileReadService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        fileReadService = new FileReadService(projectService);
    }

    @Test
    void testFileExists() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        assertTrue(fileReadService.fileExists("test.txt"));
        assertFalse(fileReadService.fileExists("nonexistent.txt"));
    }

    @Test
    void testReadFile() throws IOException {
        Path file = tempDir.resolve("test.txt");
        String content = "hello world";
        Files.writeString(file, content);

        assertEquals(content, fileReadService.readFile("test.txt"));
    }

    @Test
    void testListDirectory() throws IOException {
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createFile(tempDir.resolve("file2.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        List<String> contents = fileReadService.listDirectory(".");
        assertTrue(contents.contains("file1.txt"));
        assertTrue(contents.contains("file2.txt"));
        assertTrue(contents.contains("subdir"));
        assertEquals(3, contents.size());
    }

    @Test
    void testPathTraversalProtection() {
        assertThrows(SecurityException.class, () -> {
            fileReadService.readFile("../secret.txt");
        });
    }

    @Test
    void testReadFileOutsideRoot() throws IOException {
        Path outsideFile = tempDir.getParent().resolve("outside.txt");
        // We don't actually create it, just test the path resolution
        assertThrows(SecurityException.class, () -> {
            fileReadService.readFile("../" + outsideFile.getFileName());
        });
    }
}