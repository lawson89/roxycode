package org.roxycode.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GrepServiceTest {

    @TempDir
    Path tempDir;

    private GrepService grepService;
    private Sandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        grepService = new GrepService(sandbox);
    }

    @Test
    void testGrepBasic() throws IOException {
        Path file1 = tempDir.resolve("file1.txt");
        Files.writeString(file1, "Hello World\nAnother line\nWorld again");

        String result = grepService.grep("World", ".", "*");
        
        assertTrue(result.contains("file1.txt:1: Hello World"));
        assertTrue(result.contains("file1.txt:3: World again"));
        assertFalse(result.contains("Another line"));
    }

    @Test
    void testGrepWithFilePattern() throws IOException {
        Path file1 = tempDir.resolve("test.java");
        Files.writeString(file1, "public class Test {}");
        Path file2 = tempDir.resolve("test.txt");
        Files.writeString(file2, "public class Test {}");

        String result = grepService.grep("class", ".", "*.java");

        assertTrue(result.contains("test.java"));
        assertFalse(result.contains("test.txt"));
    }

    @Test
    void testGrepRecursive() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);
        Path file = subDir.resolve("deep.txt");
        Files.writeString(file, "Deep content");

        String result = grepService.grep("Deep", ".", "*");

        assertTrue(result.contains("subdir/deep.txt") || result.contains("subdir\\deep.txt"));
        assertTrue(result.contains("Deep content"));
    }

    @Test
    void testGrepIgnoredDirs() throws IOException {
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Path file = targetDir.resolve("ignore.txt");
        Files.writeString(file, "Should be ignored");

        String result = grepService.grep("ignored", ".", "*");

        assertEquals("No matches found.", result);
    }

    @Test
    void testGrepNotFound() {
        String result = grepService.grep("Nothing", ".", "*");
        assertEquals("No matches found.", result);
    }

    @Test
    void testPathNotFound() {
        String result = grepService.grep("foo", "nonexistent", "*");
        assertTrue(result.startsWith("❌ Path not found"));
    }

    @Test
    void testSecurity() {
        // Try to access outside of sandbox
        String result = grepService.grep("foo", "../outside", "*");
        // The sandbox resolve method throws SecurityException, which GrepService catches
        assertTrue(result.startsWith("❌ Security Error"));
    }
}
