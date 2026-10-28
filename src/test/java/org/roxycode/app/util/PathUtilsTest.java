package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void testCreateParentDirectoriesCreatesMissingParentsAndHandlesNullSafeCases() throws IOException {
        assertDoesNotThrow(() -> PathUtils.createParentDirectories(null));
        assertDoesNotThrow(() -> PathUtils.createParentDirectories(Path.of("relativeFileOnly.txt")));

        Path nestedFile = tempDir.resolve("a/b/c/file.txt");
        PathUtils.createParentDirectories(nestedFile);
        assertTrue(Files.isDirectory(nestedFile.getParent()));
    }

    @Test
    void testSanitizeSlugNormalizesTruncatesAndFallsBack() {
        assertEquals("hello-world-123", PathUtils.sanitizeSlug("  Hello, World! 123  ", 40, "fb"));
        assertEquals("fb", PathUtils.sanitizeSlug("   ---   ", 40, "fb"));
        assertEquals("fb", PathUtils.sanitizeSlug(null, 40, "fb"));
        assertEquals("abc", PathUtils.sanitizeSlug("abc-def-ghi", 4, "fb"));
        assertEquals("abcdefghij", PathUtils.sanitizeSlug("abcdefghijkl", 10, "fb"));
    }

    @Test
    void testResolveUniqueArchiveFileAppendsCollisionSuffixes() throws IOException {
        Path archiveDir = Files.createDirectories(tempDir.resolve("archive"));
        String baseId = "20250514-153000-my-slug";

        PathUtils.ArchiveFileTarget first = PathUtils.resolveUniqueArchiveFile(archiveDir, baseId);
        assertEquals(baseId, first.id());
        assertEquals(archiveDir.resolve(baseId + ".md"), first.file());
        Files.writeString(first.file(), "one");

        PathUtils.ArchiveFileTarget second = PathUtils.resolveUniqueArchiveFile(archiveDir, baseId);
        assertEquals(baseId + "-2", second.id());
        assertEquals(archiveDir.resolve(baseId + "-2.md"), second.file());
        Files.writeString(second.file(), "two");

        PathUtils.ArchiveFileTarget third = PathUtils.resolveUniqueArchiveFile(archiveDir, baseId);
        assertEquals(baseId + "-3", third.id());
        assertEquals(archiveDir.resolve(baseId + "-3.md"), third.file());
    }

    @Test
    void testGetFileNameAndLowerExtensionHelpers() {
        String winPath = "src" + (char) 92 + "main" + (char) 92 + "ChatPanel.java";
        assertEquals("ChatPanel.java", PathUtils.getFileName(winPath));
        assertEquals("", PathUtils.getFileName(null));
        assertEquals("", PathUtils.getFileName("   "));

        assertEquals("java", PathUtils.getLowerExtension("src/Main.JAVA", false));
        assertEquals("", PathUtils.getLowerExtension(".gitignore", false));
        assertEquals("gitignore", PathUtils.getLowerExtension(".gitignore", true));
    }
}
