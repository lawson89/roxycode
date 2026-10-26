package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathUtilsTest {

    @TempDir Path tempDir;

    @Test
    void testCreateParentDirectoriesCreatesMissingParentsAndHandlesNullSafeCases()
            throws IOException {
        assertDoesNotThrow(() -> PathUtils.createParentDirectories(null));
        assertDoesNotThrow(
                () -> PathUtils.createParentDirectories(Path.of("relativeFileOnly.txt")));

        Path nestedFile = tempDir.resolve("a/b/c/file.txt");
        PathUtils.createParentDirectories(nestedFile);
        assertTrue(Files.isDirectory(nestedFile.getParent()));
    }
}
