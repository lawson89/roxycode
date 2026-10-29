package org.roxycode.app.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

public final class PathUtils {

    private static final String MD_EXTENSION = ".md";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",
            Locale.ROOT);

    public record ArchiveFileTarget(String id, Path file) {
    }

    private PathUtils() {
    }

    public static void createParentDirectories(Path filePath) throws IOException {
        if (filePath != null && filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
    }

    public static String normalizeRelativePath(String path) {
        if (path == null) {
            return "";
        }
        return StringUtils.removeStart(FilenameUtils.separatorsToUnix(path), "./");
    }

    public static String toSlashPrefix(Path dir) {
        if (dir == null) {
            return "";
        }
        String normalized = FilenameUtils.separatorsToUnix(dir.toAbsolutePath().normalize().toString());
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    public static String getFileName(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        return FilenameUtils.getName(FilenameUtils.separatorsToUnix(path.trim()));
    }

    public static String getLowerExtension(String path, boolean allowLeadingDotFile) {
        String fileName = getFileName(path);
        if (fileName.isEmpty()) {
            return "";
        }
        if (!allowLeadingDotFile && StringUtils.isEmpty(FilenameUtils.getBaseName(fileName))) {
            return "";
        }
        return FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
    }

    public static String sanitizeSlug(String raw, int maxLength, String fallback) {
        String normalized = raw != null ? raw.toLowerCase(Locale.ROOT) : "";
        String sanitized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (maxLength > 0 && sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength).replaceAll("-+$", "");
        }
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    public static String formatArchiveTimestamp(String isoTimestamp) {
        if (isoTimestamp != null && !isoTimestamp.isBlank()) {
            try {
                return OffsetDateTime.parse(isoTimestamp).format(ARCHIVE_TIMESTAMP_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // Fall back to current time
            }
        }
        return OffsetDateTime.now().format(ARCHIVE_TIMESTAMP_FORMATTER);
    }

    public static ArchiveFileTarget resolveUniqueArchiveFile(Path archiveDir, String baseId) {
        String id = baseId;
        Path targetFile = archiveDir.resolve(id + MD_EXTENSION);
        int suffix = 2;
        while (Files.exists(targetFile)) {
            id = baseId + "-" + suffix;
            suffix++;
            targetFile = archiveDir.resolve(id + MD_EXTENSION);
        }
        return new ArchiveFileTarget(id, targetFile);
    }
}
