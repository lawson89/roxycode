package org.roxycode.app.util;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.springframework.core.io.ClassPathResource;

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

    public static <T> T readTomlOrDefault(Path path, Class<T> type, T fallback, TomlMapper mapper) {
        if (path == null || !Files.exists(path)) {
            return fallback;
        }
        try {
            TomlMapper effective = Objects.requireNonNullElse(mapper, AiConfig.SHARED_TOML_MAPPER);
            return Objects.requireNonNullElse(effective.readValue(path.toFile(), type), fallback);
        } catch (IOException e) {
            return fallback;
        }
    }

    public static void writeToml(Path path, Object value, TomlMapper mapper) throws IOException {
        createParentDirectories(path);
        Objects.requireNonNullElse(mapper, AiConfig.SHARED_TOML_MAPPER).writeValue(path.toFile(), value);
    }

    public static String normalizeRelativePath(String path) {
        return StringUtils.removeStart(FilenameUtils.separatorsToUnix(StringUtils.defaultString(path)), "./");
    }

    public static String getFileName(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        return FilenameUtils.getName(FilenameUtils.separatorsToUnix(path.trim()));
    }

    public static String getLowerExtension(String path, boolean allowLeadingDotFile) {
        String fileName = getFileName(path);
        if (fileName.isEmpty() || (!allowLeadingDotFile && StringUtils.isEmpty(FilenameUtils.getBaseName(fileName)))) {
            return "";
        }
        return FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
    }

    public static String sanitizeSlug(String raw, int maxLength, String fallback) {
        String normalized = StringUtils.defaultString(raw).toLowerCase(Locale.ROOT);
        String sanitized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (maxLength > 0 && sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength).replaceAll("-+$", "");
        }
        return StringUtils.defaultIfEmpty(sanitized, fallback);
    }

    public static String formatArchiveTimestamp(String isoTimestamp) {
        if (StringUtils.isNotBlank(isoTimestamp)) {
            try {
                return OffsetDateTime.parse(isoTimestamp).format(ARCHIVE_TIMESTAMP_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // Fall back to current time
            }
        }
        return OffsetDateTime.now().format(ARCHIVE_TIMESTAMP_FORMATTER);
    }

    public static String formatTimestampDisplay(String rawTimestamp, int maxLength, String emptyFallback) {
        if (StringUtils.isBlank(rawTimestamp)) {
            return emptyFallback;
        }
        String trimmed = rawTimestamp.trim();
        if (maxLength > 0 && trimmed.length() > maxLength) {
            trimmed = trimmed.substring(0, maxLength);
        }
        return trimmed.replace('T', ' ');
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

    public static ArchiveFileTarget prepareArchiveTarget(Path archiveDir, String isoTimestamp, String title,
            int maxSlugLength, String fallbackSlug) throws IOException {
        Files.createDirectories(archiveDir);
        String tsPrefix = formatArchiveTimestamp(isoTimestamp);
        String slug = sanitizeSlug(title, maxSlugLength, fallbackSlug);
        return resolveUniqueArchiveFile(archiveDir, tsPrefix + "-" + slug);
    }

    @SuppressWarnings("PMD.UseProperClassLoader")
    public static String readClasspathResourceUtf8(String path, Class<?> anchorClass) throws IOException {
        ClassPathResource resource = new ClassPathResource(path, anchorClass.getClassLoader());
        try (InputStream is = resource.getInputStream()) {
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        }
    }
}
