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
import org.roxycode.app.service.ProjectService;

public class FileReadServiceTest {

    @TempDir Path tempDir;

    private ProjectService projectService;
    private FileReadService fileReadService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
        fileReadService = new FileReadService(projectService);
    }

    @Test
    void testFileExists() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello");
        assertTrue(fileReadService.fileExists("test.txt"));
        assertFalse(fileReadService.fileExists("nonexistent.txt"));
    }

    @Test
    void testReadFile() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world");
        assertEquals("hello world", fileReadService.readFile("test.txt"));
    }

    @Test
    void testReadLinesReadFileNumberedAndGetLineCount() throws IOException {
        Files.writeString(tempDir.resolve("empty.txt"), "");
        assertEquals(0, fileReadService.getLineCount("empty.txt"));
        assertEquals("", fileReadService.readFileNumbered("empty.txt"));
        assertEquals("", fileReadService.readLines("empty.txt", 1, 5));

        Files.writeString(tempDir.resolve("single.txt"), "only line");
        assertEquals(1, fileReadService.getLineCount("single.txt"));
        assertEquals("1: only line", fileReadService.readFileNumbered("single.txt"));
        assertEquals("1: only line", fileReadService.readLines("single.txt", 1, 10));
        assertEquals("", fileReadService.readLines("single.txt", 2, 5));

        Files.writeString(tempDir.resolve("multi.txt"), "alpha\nbeta\ngamma\ndelta\n");
        assertEquals(4, fileReadService.getLineCount("multi.txt"));
        assertEquals(
                "1: alpha\n2: beta\n3: gamma\n4: delta",
                fileReadService.readFileNumbered("multi.txt"));
        assertEquals("2: beta\n3: gamma", fileReadService.readLines("multi.txt", 2, 3));
        assertEquals("3: gamma\n4: delta", fileReadService.readLines("multi.txt", 3, 99));

        assertThrows(
                IllegalArgumentException.class, () -> fileReadService.readLines("multi.txt", 0, 2));
        assertThrows(
                IllegalArgumentException.class, () -> fileReadService.readLines("multi.txt", 3, 2));
    }

    @Test
    void testListDirectory() throws IOException {
        Files.createFile(tempDir.resolve("file2.txt"));
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));
        assertEquals(
                List.of("file1.txt", "file2.txt", "subdir/"), fileReadService.listDirectory("."));
    }

    @Test
    void testPathTraversalProtection() {
        assertThrows(SecurityException.class, () -> fileReadService.readFile("../secret.txt"));
    }

    @Test
    void testReadFileOutsideRoot() {
        Path outsideFile = tempDir.getParent().resolve("outside.txt");
        assertThrows(
                SecurityException.class,
                () -> fileReadService.readFile("../" + outsideFile.getFileName()));
    }

    @Test
    void testReadFileNonExistentOrDirectoryThrowsIllegalArgumentException() throws IOException {
        Files.createDirectory(tempDir.resolve("adir"));
        assertThrows(IllegalArgumentException.class, () -> fileReadService.readFile("missing.txt"));
        assertThrows(IllegalArgumentException.class, () -> fileReadService.readFile("adir"));
    }

    @Test
    void testListDirectoryNonExistentOrRegularFileThrowsIllegalArgumentException()
            throws IOException {
        Files.writeString(tempDir.resolve("afile.txt"), "data");
        assertThrows(
                IllegalArgumentException.class, () -> fileReadService.listDirectory("missing-dir"));
        assertThrows(
                IllegalArgumentException.class, () -> fileReadService.listDirectory("afile.txt"));
    }

    @Test
    void testIsBinaryFile() throws IOException {
        assertFalse(FileReadService.isBinaryFile(null));
        assertFalse(FileReadService.isBinaryFile(tempDir));
        assertFalse(FileReadService.isBinaryFile(tempDir.resolve("missing.bin")));

        Path textFile = Files.writeString(tempDir.resolve("plain.txt"), "Hello UTF-8 text\nLine 2");
        assertFalse(FileReadService.isBinaryFile(textFile));

        Path binFile =
                Files.write(tempDir.resolve("binary.dat"), new byte[] {0x48, 0x65, 0x00, 0x6c});
        assertTrue(FileReadService.isBinaryFile(binFile));
    }
}
