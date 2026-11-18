package org.roxycode.app.util;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.springframework.core.io.ClassPathResource;

/** File path, serialization, hashing, and archive naming utilities. */
public final class PathUtils {
    private static final String CURRENT_DIR = ".";
    private static final String USER_HOME_TOKEN = "~";
    private static final String MD_EXTENSION = ".md";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",
            Locale.ROOT);
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    /** Target file for archiving, including its unique ID and path. */
    public record ArchiveFileTarget(String id, Path file) {
    }

    private PathUtils() {
    }

    /** Creates parent directories for the given file path if they do not exist. */
    public static void createParentDirectories(Path filePath) throws IOException {
        if (filePath != null && filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
    }

    /** Reads a TOML file into {@code type}, returning {@code fallback} if missing or invalid. */
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

    /** Writes {@code value} to a TOML file, creating parent directories if needed. */
    public static void writeToml(Path path, Object value, TomlMapper mapper) throws IOException {
        createParentDirectories(path);
        Objects.requireNonNullElse(mapper, AiConfig.SHARED_TOML_MAPPER).writeValue(path.toFile(), value);
    }

    /** Reads a JSON file into {@code type}, returning {@code fallback} if missing or invalid. */
    public static <T> T readJsonOrDefault(Path path, Class<T> type, T fallback) {
        if (path == null || !Files.exists(path)) {
            return fallback;
        }
        try {
            return Objects.requireNonNullElse(AiConfig.SHARED_OBJECT_MAPPER.readValue(path.toFile(), type), fallback);
        } catch (IOException e) {
            return fallback;
        }
    }

    /** Writes {@code value} to a JSON file with pretty printing. */
    public static void writeJson(Path path, Object value) throws IOException {
        createParentDirectories(path);
        AiConfig.SHARED_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    /** Deletes {@code path} if it exists, ignoring any {@link IOException}. */
    public static void deleteIfExistsQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Ignore cleanup errors
        }
    }

    /** Sets owner-only ({@code rw-------}) permissions on {@code path} when POSIX attributes are supported. */
    public static void setOwnerOnlyPermissionsIfPosix(Path path) throws IOException {
        if (path == null || !Files.exists(path)
                || !path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Ignore on non-POSIX filesystems
        }
    }

    /** Expands a leading {@code ~}, {@code ~/...}, or {@code ~\...} prefix against {@code user.home}. */
    public static Path expandUserHome(String rawPath) {
        String trimmed = StringUtils.trimToEmpty(rawPath);
        String userHome = System.getProperty("user.home");
        if (StringUtils.isNotBlank(userHome)) {
            if (USER_HOME_TOKEN.equals(trimmed)) {
                return Path.of(userHome);
            }
            if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
                return Path.of(userHome).resolve(trimmed.substring(2));
            }
        }
        return Path.of(trimmed);
    }

    /** Removes leading {@code ./} prefixes from {@code path}. */
    public static String stripLeadingCurrentDir(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String unix = FilenameUtils.separatorsToUnix(path.trim());
        while (unix.startsWith("./")) {
            unix = unix.substring(2);
        }
        return unix;
    }

    /** Normalizes a relative path string, converting separators to Unix style. */
    public static String normalizeRelativePath(String path) {
        String unix = stripLeadingCurrentDir(path);
        if (unix.isEmpty()) {
            return "";
        }
        try {
            String normalized = FilenameUtils.separatorsToUnix(Path.of(unix).normalize().toString());
            if (CURRENT_DIR.equals(normalized)) {
                return "";
            }
            return normalized;
        } catch (InvalidPathException e) {
            return unix;
        }
    }

    /** Returns the file name from {@code path}. */
    public static String getFileName(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        return FilenameUtils.getName(FilenameUtils.separatorsToUnix(path.trim()));
    }

    /** Returns the file name without extension from {@code path}. */
    public static String getBaseName(String path) {
        String fileName = getFileName(path);
        if (fileName.isEmpty() || fileName.lastIndexOf('.') <= 0) {
            return fileName;
        }
        return FilenameUtils.removeExtension(fileName);
    }

    /** Returns the lowercase file extension from {@code path}, or empty string if none. */
    public static String getLowerExtension(String path, boolean allowLeadingDotFile) {
        String fileName = getFileName(path);
        if (fileName.isEmpty() || (!allowLeadingDotFile && StringUtils.isEmpty(FilenameUtils.getBaseName(fileName)))) {
            return "";
        }
        return FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
    }

    /** Sanitizes {@code raw} into a lowercase alphanumeric-and-hyphen slug. */
    public static String sanitizeSlug(String raw, int maxLength, String fallback) {
        String normalized = StringUtils.defaultString(raw).toLowerCase(Locale.ROOT);
        String sanitized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (maxLength > 0 && sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength).replaceAll("-+$", "");
        }
        return StringUtils.defaultIfEmpty(sanitized, fallback);
    }

    static String formatArchiveTimestamp(String isoTimestamp) {
        if (StringUtils.isNotBlank(isoTimestamp)) {
            try {
                return OffsetDateTime.parse(isoTimestamp).format(ARCHIVE_TIMESTAMP_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // Fall back to current time
            }
        }
        return OffsetDateTime.now().format(ARCHIVE_TIMESTAMP_FORMATTER);
    }

    /** Formats an ISO-8601 timestamp string for display. */
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

    static ArchiveFileTarget resolveUniqueArchiveFile(Path archiveDir, String baseId) {
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

    /** Prepares a unique archive file target based on a timestamp and title. */
    public static ArchiveFileTarget prepareArchiveTarget(Path archiveDir, String isoTimestamp, String title,
            int maxSlugLength, String fallbackSlug) throws IOException {
        Files.createDirectories(archiveDir);
        String tsPrefix = formatArchiveTimestamp(isoTimestamp);
        String slug = sanitizeSlug(title, maxSlugLength, fallbackSlug);
        return resolveUniqueArchiveFile(archiveDir, tsPrefix + "-" + slug);
    }

    /** Computes the SHA-256 hash of {@code input} and returns the first {@code byteCount} bytes as hex. */
    public static String computeSha256Hex(String input, int byteCount) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(input).getBytes(StandardCharsets.UTF_8));
            int len = Math.min(Math.max(1, byteCount), digest.length);
            return HexFormat.of().formatHex(digest, 0, len);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** Reads a classpath resource as a UTF-8 string using {@code anchorClass}'s class loader. */
    @SuppressWarnings("PMD.UseProperClassLoader")
    public static String readClasspathResourceUtf8(String path, Class<?> anchorClass) throws IOException {
        ClassPathResource resource = new ClassPathResource(path, anchorClass.getClassLoader());
        try (InputStream is = resource.getInputStream()) {
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        }
    }
}