package org.roxycode.core;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Sandbox {
    private final Path projectRoot;

    // Initializes holding the projectRoot [cite: 49]
    public Sandbox(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * Resolves a path relative to the root and validates it.
     * @param path The relative path string.
     * @return The resolved absolute path.
     * @throws SecurityException if result is not a child of projectRoot[cite: 50, 51].
     */
    public Path resolve(String path) {
        // Resolve and normalize [cite: 49]
        Path resolved = projectRoot.resolve(path).normalize();

        // Security check: ensure the resolved path starts with the project root
        if (!resolved.startsWith(projectRoot)) {
            throw new SecurityException("Access denied: Path escapes sandbox root. " + resolved);
        }

        return resolved;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }
}