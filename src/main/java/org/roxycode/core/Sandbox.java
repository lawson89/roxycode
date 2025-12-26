package org.roxycode.core;

import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;

@Singleton
public class Sandbox {
    // Default to current directory, but allow updates
    private Path sandboxRoot = Paths.get(".").toAbsolutePath().normalize();

    /**
     * Updates the Sandbox root. Called by GenAIService when --root is passed.
     */
    public void setRoot(String rootPath) {
        this.sandboxRoot = Paths.get(rootPath).toAbsolutePath().normalize();
    }

    public Path getRoot() {
        return sandboxRoot;
    }

    /**
     * Validates that a path is safe and inside the sandbox.
     * @param pathStr The path to check (relative or absolute)
     * @return The resolved, absolute Path
     * @throws SecurityException if the path is outside the root
     */
    public Path resolve(String pathStr) {
        Path inputPath = Paths.get(pathStr);

        // Resolve against root if relative
        Path resolved = inputPath.isAbsolute()
                ? inputPath
                : sandboxRoot.resolve(inputPath);

        resolved = resolved.toAbsolutePath().normalize();

        if (!resolved.startsWith(sandboxRoot)) {
            throw new SecurityException("Access denied: Path escapes sandbox root. " + resolved);
        }

        return resolved;
    }
}