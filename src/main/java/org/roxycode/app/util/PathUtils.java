package org.roxycode.app.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Utility methods for common file system path operations. */
public final class PathUtils {

    private PathUtils() {}

    /**
     * Creates all nonexistent parent directories for the given file path. Safe no-op if the path or
     * its parent is {@code null}.
     *
     * @param filePath the target file path whose parent directories should be created
     * @throws IOException if an I/O error occurs while creating directories
     */
    public static void createParentDirectories(Path filePath) throws IOException {
        if (filePath != null && filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
    }
}
