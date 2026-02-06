package org.roxycode.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SandboxFileSystemTest {

    @TempDir
    Path tempDir;

    @Test
    public void testSandboxing() throws IOException {
        Path root = tempDir.resolve("root");
        Files.createDirectory(root);
        Files.writeString(root.resolve("test.txt"), "hello");

        URI uri = URI.create("sandbox://test");
        Map<String, Object> env = new HashMap<>();
        env.put("root", root.toString());
        env.put("readOnly", false);

        try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
            Path p = fs.getPath("/test.txt");
            assertEquals("hello", Files.readString(p));
            
            // Check absolute mapping
            Path abs = fs.getPath("test.txt").toAbsolutePath();
            assertEquals("/test.txt", abs.toString());
            
            // Check escape attempt - going up from root stays at root
            Path escape = fs.getPath("/../../");
            assertTrue(Files.isSameFile(fs.getPath("/"), escape.normalize()));
            
            // Check that we can't see the parent directory of root
            Path outside = fs.getPath("/../other"); // normalized to /other
            // This should map to <root>/other, which doesn't exist.
            assertFalse(Files.exists(outside));
        }
    }

    @Test
    public void testReadOnly() throws IOException {
        Path root = tempDir.resolve("readonly-root");
        Files.createDirectory(root);

        URI uri = URI.create("sandbox://readonly");
        Map<String, Object> env = new HashMap<>();
        env.put("root", root.toString());
        env.put("readOnly", true);

        try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
            Path p = fs.getPath("/new.txt");
            assertThrows(AccessDeniedException.class, () -> {
                Files.writeString(p, "fail");
            });
        }
    }
}
