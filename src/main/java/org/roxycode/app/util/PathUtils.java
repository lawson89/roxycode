package org.roxycode.app.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Utility methods for common file system path and slug operations. */
public final class PathUtils {

    private static final String MD_EXTENSION = ".md";

    /**
     * Holds a resolved collision-free archive identifier and its target markdown file path.
     *
     * @param id the resolved unique archive id (e.g. {@code 20250514-153000-slug} or {@code
     *     20250514-153000-slug-2})
     * @param file the resolved target {@code .md} file path
     */
    public record ArchiveFileTarget(String id, Path file) {}

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

    /**
     * Converts a raw string into a lowercase alphanumeric-and-hyphen slug, truncated to {@code
     * maxLength} characters (with any trailing hyphens stripped), or returns {@code fallback} if
     * the resulting slug is empty.
     *
     * @param raw the raw input string
     * @param maxLength the maximum slug length (if positive)
     * @param fallback the fallback string if the sanitized slug is empty
     * @return the sanitized slug or fallback
     */
    public static String sanitizeSlug(String raw, int maxLength, String fallback) {
        String normalized = raw != null ? raw.toLowerCase(Locale.ROOT) : "";
        String sanitized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (maxLength > 0 && sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength).replaceAll("-+$", "");
        }
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    /**
     * Resolves a collision-safe markdown archive file path ({@code <baseId>.md}, {@code
     * <baseId>-2.md}, {@code <baseId>-3.md}, ...) within the given archive directory.
     *
     * @param archiveDir the target archive directory
     * @param baseId the base archive identifier (typically {@code <timestamp>-<slug>})
     * @return the unique {@link ArchiveFileTarget} containing the resolved id and path
     */
    public static ArchiveFileTarget resolveUniqueArchiveFile(Path archiveDir, String baseId) {
        String id = baseId;
        Path targetFile = archiveDir.resolve(id + MD_EXTENSION);
        int suffix = 2;
        while (Files.exists(targetFile)) {
            id = baseId + "-" + suffix;
            targetFile = archiveDir.resolve(id + MD_EXTENSION);
            suffix++;
        }
        return new ArchiveFileTarget(id, targetFile);
    }
}
