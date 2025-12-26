package org.roxycode.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    @Test
    void testValidResolution() {
        Path root = Paths.get(".").toAbsolutePath();
        Sandbox sandbox = new Sandbox(root);

        Path resolved = sandbox.resolve("src/main");
        assertEquals(root.resolve("src/main").normalize(), resolved);
    }

    @Test
    void testTraversalThrowsException() {
        // Verify that ../ traversal attempts throw exceptions
        Path root = Paths.get("/tmp/roxycode");
        Sandbox sandbox = new Sandbox(root);

        assertThrows(SecurityException.class, () -> {
            sandbox.resolve("../secret.txt");
        });

        assertThrows(SecurityException.class, () -> {
            sandbox.resolve("subdir/../../system");
        });
    }
}