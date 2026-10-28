package org.roxycode.app.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

public final class PathUtils {

    private static final String MD_EXTENSION = ".md";

    public record ArchiveFileTarget(String id, Path file) {
    }

    private PathUtils() {
    }

    public static void createParentDirectories(Path filePath) throws IOException {
        if (filePath != null && filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
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

    public static String getLowerExtension(Path path, boolean allowLeadingDotFile) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        return getLowerExtension(path.getFileName().toString(), allowLeadingDotFile);
    }

    public static String sanitizeSlug(String raw, int maxLength, String fallback) {
        String normalized = raw != null ? raw.toLowerCase(Locale.ROOT) : "";
        String sanitized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (maxLength > 0 && sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength).replaceAll("-+$", "");
        }
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    public static ArchiveFileTarget resolveUniqueArchiveFile(Path archiveDir, String baseId) {
        String id = baseId;
        Path targetFile = archiveDir.resolve(id + MD_EXTENSION);
        int suffix = 2;
        while (Files.exists(targetFile)) {
            id = baseId + "-" + suffix++;
            targetFile = archiveDir.resolve(id + MD_EXTENSION);
        }
        return new ArchiveFileTarget(id, targetFile);
    }
}
