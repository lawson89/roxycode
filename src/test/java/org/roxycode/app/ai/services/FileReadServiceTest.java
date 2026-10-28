package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;

public class FileReadServiceTest {

    @TempDir
    Path tempDir;

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
        String nl = String.valueOf((char) 10);
        Files.writeString(tempDir.resolve("empty.txt"), "");
        assertEquals(0, fileReadService.getLineCount("empty.txt"));
        assertEquals("", fileReadService.readFileNumbered("empty.txt"));
        assertEquals("", fileReadService.readLines("empty.txt", 1, 5));

        Files.writeString(tempDir.resolve("single.txt"), "only line");
        assertEquals(1, fileReadService.getLineCount("single.txt"));
        assertEquals("1: only line", fileReadService.readFileNumbered("single.txt"));
        assertEquals("1: only line", fileReadService.readLines("single.txt", 1, 10));
        assertEquals("", fileReadService.readLines("single.txt", 2, 5));

        Files.writeString(tempDir.resolve("multi.txt"), String.join(nl, "alpha", "beta", "gamma", "delta") + nl);
        assertEquals(4, fileReadService.getLineCount("multi.txt"));
        assertEquals(String.join(nl, "1: alpha", "2: beta", "3: gamma", "4: delta"),
                fileReadService.readFileNumbered("multi.txt"));
        assertEquals(String.join(nl, "2: beta", "3: gamma"), fileReadService.readLines("multi.txt", 2, 3));
        assertEquals(String.join(nl, "3: gamma", "4: delta"), fileReadService.readLines("multi.txt", 3, 99));

        assertThrows(IllegalArgumentException.class, () -> fileReadService.readLines("multi.txt", 0, 2));
        assertThrows(IllegalArgumentException.class, () -> fileReadService.readLines("multi.txt", 3, 2));
    }

    @Test
    void testReadFilesAndReadFilesNumberedWithPartialErrors() throws IOException {
        String nl = String.valueOf((char) 10);
        assertTrue(fileReadService.readFiles((List<String>) null).isEmpty());
        assertTrue(fileReadService.readFiles((String[]) null).isEmpty());
        assertTrue(fileReadService.readFiles(new String[0]).isEmpty());
        assertTrue(fileReadService.readFilesNumbered((String[]) null).isEmpty());
        assertTrue(fileReadService.readFilesNumbered(new String[0]).isEmpty());
        assertTrue(fileReadService.readFiles(List.of()).isEmpty());

        Files.writeString(tempDir.resolve("a.txt"), "alpha" + nl + "beta");
        Files.writeString(tempDir.resolve("b.txt"), "gamma");
        Files.createDirectory(tempDir.resolve("subdir"));
        Files.write(tempDir.resolve("bin.dat"), new byte[]{0x01, 0x00, 0x02});

        List<String> paths = new ArrayList<>();
        paths.add("a.txt");
        paths.add("missing.txt");
        paths.add("subdir");
        paths.add("bin.dat");
        paths.add("../outside.txt");
        paths.add("b.txt");
        paths.add("a.txt");
        paths.add("");
        paths.add(null);

        Map<String, String> plain = fileReadService.readFiles(paths);
        assertEquals(List.of("a.txt", "missing.txt", "subdir", "bin.dat", "../outside.txt", "b.txt"),
                new ArrayList<>(plain.keySet()));
        assertEquals("alpha" + nl + "beta", plain.get("a.txt"));
        assertTrue(plain.get("missing.txt").startsWith("Error: File does not exist:"));
        assertTrue(plain.get("subdir").startsWith("Error: Path is not a regular file:"));
        assertTrue(plain.get("bin.dat").startsWith("Error: Binary file cannot be read as text:"));
        assertTrue(plain.get("../outside.txt").startsWith("Error:"));
        assertEquals("gamma", plain.get("b.txt"));

        Map<String, String> numbered = fileReadService.readFilesNumbered(List.of("a.txt", "missing.txt", "b.txt"));
        assertEquals("1: alpha" + nl + "2: beta", numbered.get("a.txt"));
        assertTrue(numbered.get("missing.txt").startsWith("Error: File does not exist:"));
        assertEquals("1: gamma", numbered.get("b.txt"));

        Map<String, String> arrayPlain = fileReadService.readFiles("a.txt", "b.txt");
        assertEquals(Map.of("a.txt", "alpha" + nl + "beta", "b.txt", "gamma"), arrayPlain);

        Map<String, String> arrayNumbered = fileReadService.readFilesNumbered("a.txt", "b.txt");
        assertEquals(Map.of("a.txt", "1: alpha" + nl + "2: beta", "b.txt", "1: gamma"), arrayNumbered);
    }

    @Test
    void testReadFilesEnforcesMaxBatchFilesCap() throws IOException {
        List<String> manyPaths = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String name = "file" + i + ".txt";
            Files.writeString(tempDir.resolve(name), "content " + i);
            manyPaths.add(name);
        }

        Map<String, String> result = fileReadService.readFiles(manyPaths);
        assertEquals(FileReadService.MAX_BATCH_FILES + 1, result.size());
        assertEquals("content 1", result.get("file1.txt"));
        assertEquals("content 25", result.get("file25.txt"));
        assertFalse(result.containsKey("file26.txt"));
        assertTrue(result.get(FileReadService.TRUNCATED_KEY).contains("Batch file limit (25 files) reached"));
    }

    @Test
    void testReadFilesEnforcesMaxBatchCharsCap() throws IOException {
        String chunk = "x".repeat(150_000);
        Files.writeString(tempDir.resolve("large1.txt"), chunk);
        Files.writeString(tempDir.resolve("large2.txt"), chunk);
        Files.writeString(tempDir.resolve("large3.txt"), "extra");

        Map<String, String> result = fileReadService.readFiles(List.of("large1.txt", "large2.txt", "large3.txt"));
        assertEquals(chunk, result.get("large1.txt"));
        assertTrue(result.get("large2.txt").contains("[Truncated at batch character limit]"));
        assertFalse(result.containsKey("large3.txt"));
        assertTrue(result.get(FileReadService.TRUNCATED_KEY).contains("Batch character limit"));
    }

    @Test
    void testListDirectory() throws IOException {
        Files.createFile(tempDir.resolve("file2.txt"));
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));
        assertEquals(List.of("file1.txt", "file2.txt", "subdir/"), fileReadService.listDirectory("."));
    }

    @Test
    void testPathTraversalProtection() {
        assertThrows(SecurityException.class, () -> fileReadService.readFile("../secret.txt"));
    }

    @Test
    void testReadFileOutsideRoot() {
        Path outsideFile = tempDir.getParent().resolve("outside.txt");
        assertThrows(SecurityException.class, () -> fileReadService.readFile("../" + outsideFile.getFileName()));
    }

    @Test
    void testReadFileNonExistentOrDirectoryThrowsIllegalArgumentException() throws IOException {
        Files.createDirectory(tempDir.resolve("adir"));
        assertThrows(IllegalArgumentException.class, () -> fileReadService.readFile("missing.txt"));
        assertThrows(IllegalArgumentException.class, () -> fileReadService.readFile("adir"));
    }

    @Test
    void testListDirectoryNonExistentOrRegularFileThrowsIllegalArgumentException() throws IOException {
        Files.writeString(tempDir.resolve("afile.txt"), "data");
        assertThrows(IllegalArgumentException.class, () -> fileReadService.listDirectory("missing-dir"));
        assertThrows(IllegalArgumentException.class, () -> fileReadService.listDirectory("afile.txt"));
    }

    @Test
    void testIsBinaryFile() throws IOException {
        assertFalse(FileReadService.isBinaryFile(null));
        assertFalse(FileReadService.isBinaryFile(tempDir));
        assertFalse(FileReadService.isBinaryFile(tempDir.resolve("missing.bin")));

        Path textFile = Files.writeString(tempDir.resolve("plain.txt"), "Hello UTF-8 text" + (char) 10 + "Line 2");
        assertFalse(FileReadService.isBinaryFile(textFile));

        Path binFile = Files.write(tempDir.resolve("binary.dat"), new byte[]{0x48, 0x65, 0x00, 0x6c});
        assertTrue(FileReadService.isBinaryFile(binFile));
    }
}
