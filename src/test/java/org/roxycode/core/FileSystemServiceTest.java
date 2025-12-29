package org.roxycode.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.tools.service.FileSystemService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemServiceTest {

    @TempDir
    Path tempDir;

    private Sandbox sandbox;
    private FileSystemService fileSystemService;

    @BeforeEach
    void setUp() {
        sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        fileSystemService = new FileSystemService(sandbox);
    }

    @Test
    void testWriteAndReadFile() throws IOException {
        String filename = "test.txt";
        String content = "Hello, Roxy!";
        
        fileSystemService.writeFile(filename, content);
        
        Path filePath = tempDir.resolve(filename);
        assertTrue(Files.exists(filePath));
        assertEquals(content, Files.readString(filePath));
        
        String readContent = fileSystemService.readFile(filename);
        assertEquals(content, readContent);
    }

    @Test
    void testWriteFileWithParentDirectories() throws IOException {
        String filename = "subdir1/subdir2/test.txt";
        String content = "Nested content";
        
        fileSystemService.writeFile(filename, content);
        
        Path filePath = tempDir.resolve(filename);
        assertTrue(Files.exists(filePath));
        assertEquals(content, Files.readString(filePath));
    }

    @Test
    void testDeleteFile() throws IOException {
        String filename = "delete-me.txt";
        Path filePath = tempDir.resolve(filename);
        Files.writeString(filePath, "delete me");
        
        assertTrue(Files.exists(filePath));
        fileSystemService.delete(filename);
        assertFalse(Files.exists(filePath));
    }

    @Test
    void testDeleteDirectory() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);
        Files.writeString(subDir.resolve("file.txt"), "content");
        
        assertTrue(Files.exists(subDir));
        fileSystemService.delete("subdir");
        assertFalse(Files.exists(subDir));
    }

    @Test
    void testDeleteSafetyChecks() {
        // Cannot delete root
        assertThrows(IOException.class, () -> fileSystemService.delete("."));
        
        // Cannot delete .git folder (even if it doesn't exist, sandbox would resolve it)
        assertThrows(IOException.class, () -> fileSystemService.delete(".git"));
        assertThrows(IOException.class, () -> fileSystemService.delete(".git/config"));
    }

    @Test
    void testListFiles() throws IOException {
        Files.writeString(tempDir.resolve("file1.txt"), "1");
        Files.writeString(tempDir.resolve("file2.log"), "2");
        Path subDir = tempDir.resolve("sub");
        Files.createDirectory(subDir);
        Files.writeString(subDir.resolve("file3.txt"), "3");

        // Non-recursive, wildcard
        String list = fileSystemService.listFiles(".", "*.txt", false);
        assertEquals("file1.txt", list.trim());

        // Recursive, wildcard
        list = fileSystemService.listFiles(".", "*.txt", true);
        String[] lines = list.split("\n");
        assertEquals(2, lines.length);
        assertTrue(list.contains("file1.txt"));
        assertTrue(list.contains("sub/file3.txt"));
    }

    @Test
    void testTree() throws IOException {
        Files.createDirectory(tempDir.resolve("dir1"));
        Files.writeString(tempDir.resolve("dir1/file1.txt"), "1");
        Files.createDirectory(tempDir.resolve("dir2"));

        String tree = fileSystemService.tree(".");
        
        // The tree starts with the root dir name
        assertTrue(tree.contains(tempDir.getFileName().toString()));
        assertTrue(tree.contains("├── dir1/"));
        assertTrue(tree.contains("│   └── file1.txt"));
        assertTrue(tree.contains("└── dir2/"));
    }

    @Test
    void testReplaceInFile() throws IOException {
        String filename = "replace.txt";
        Files.writeString(tempDir.resolve(filename), "Hello World");
        
        String result = fileSystemService.replaceInFile(filename, "World", "Roxy");
        assertEquals("Successfully updated replace.txt", result);
        assertEquals("Hello Roxy", Files.readString(tempDir.resolve(filename)));
        
        result = fileSystemService.replaceInFile(filename, "NotFound", "Nothing");
        assertEquals("No changes made to replace.txt", result);
    }

    @Test
    void testReadFiles() throws IOException {
        Files.writeString(tempDir.resolve("f1.txt"), "content 1");
        Files.writeString(tempDir.resolve("f2.txt"), "content 2");
        
        String output = fileSystemService.readFiles(List.of("f1.txt", "f2.txt", "missing.txt"));
        
        assertTrue(output.contains("--- File: f1.txt ---"));
        assertTrue(output.contains("content 1"));
        assertTrue(output.contains("--- File: f2.txt ---"));
        assertTrue(output.contains("content 2"));
        assertTrue(output.contains("--- File: missing.txt ---"));
        assertTrue(output.contains("Error reading file"));
    }
}
