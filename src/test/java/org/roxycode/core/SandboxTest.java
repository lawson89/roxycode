package org.roxycode.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void testResolveValidPath() {
        // Old: Sandbox sandbox = new Sandbox(tempDir);
        // New: Use empty constructor + setRoot
        Sandbox sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());

        Path result = sandbox.resolve("test.txt");
        assertEquals(tempDir.resolve("test.txt").toAbsolutePath(), result);
    }

    @Test
    void testResolveRelativePath() {
        Sandbox sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());

        Path result = sandbox.resolve("subdir/test.txt");
        assertEquals(tempDir.resolve("subdir/test.txt").toAbsolutePath(), result);
    }

    @Test
    void testPreventPathTraversal() {
        Sandbox sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());

        // This attempts to go up out of the temp dir
        assertThrows(SecurityException.class, () -> sandbox.resolve("../secret.txt"));
    }

    @Test
    void testPreventRootTraversal() {
        Sandbox sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());

        assertThrows(SecurityException.class, () -> sandbox.resolve("/etc/passwd"));
    }
}