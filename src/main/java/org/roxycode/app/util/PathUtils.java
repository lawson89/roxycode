package org.roxycode.app.util;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.springframework.core.io.ClassPathResource;

public final class PathUtils {
    private static final String CURRENT_DIR = ".";

    private static final String MD_EXTENSION = ".md";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",
            Locale.ROOT);
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    /**
     * Represents a target file for archiving, including its unique ID and path.
     *
     * @param id
     *            the unique ID for the archived file
     * @param file
     *            the path to the archived file
     */
    public record ArchiveFileTarget(String id, Path file) {
    }

    private PathUtils() {
    }

    /**
     * Creates the parent directories for the given file path if they do not exist.
     *
     * @param filePath
     *            the file path
     * @throws IOException
     *             if directory creation fails
     */
    public static void createParentDirectories(Path filePath) throws IOException {
        if (filePath != null && filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
    }

    /**
     * Reads a TOML file into the specified type, returning a fallback if the file does not exist or parsing fails.
     *
     * @param <T>
     *            the result type
     * @param path
     *            the path to the TOML file
     * @param type
     *            the target class
     * @param fallback
     *            the fallback value
     * @param mapper
     *            the TomlMapper to use (optional)
     * @return the parsed object or fallback
     */
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

    /**
     * Writes the given object to a TOML file.
     *
     * @param path
     *            the target path
     * @param value
     *            the object to write
     * @param mapper
     *            the TomlMapper to use (optional)
     * @throws IOException
     *             if writing fails
     */
    public static void writeToml(Path path, Object value, TomlMapper mapper) throws IOException {
        createParentDirectories(path);
        Objects.requireNonNullElse(mapper, AiConfig.SHARED_TOML_MAPPER).writeValue(path.toFile(), value);
    }

    /**
     * Reads a JSON file into the specified type, returning a fallback if the file does not exist or parsing fails.
     *
     * @param <T>
     *            the result type
     * @param path
     *            the path to the JSON file
     * @param type
     *            the target class
     * @param fallback
     *            the fallback value
     * @return the parsed object or fallback
     */
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

    /**
     * Writes the given object to a JSON file with pretty printing.
     *
     * @param path
     *            the target path
     * @param value
     *            the object to write
     * @throws IOException
     *             if writing fails
     */
    public static void writeJson(Path path, Object value) throws IOException {
        createParentDirectories(path);
        AiConfig.SHARED_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    /**
     * Deletes the file or directory if it exists, ignoring any IOExceptions.
     *
     * @param path
     *            the path to delete
     */
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

    /**
     * Sets owner-only (rw-------) permissions on the given path if the file system supports POSIX attributes.
     *
     * @param path
     *            the path to secure
     * @throws IOException
     *             if setting permissions fails
     */
    public static void setOwnerOnlyPermissionsIfPosix(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Ignore on non-POSIX filesystems
        }
    }

    /**
     * Removes leading './' prefixes from the given path string.
     *
     * @param path
     *            the path string
     * @return the path without leading current directory markers
     */
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

    /**
     * Normalizes the given relative path string, converting separators to Unix style.
     *
     * @param path
     *            the path string
     * @return the normalized relative path
     */
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

    /**
     * Returns the file name from the given path string.
     *
     * @param path
     *            the path string
     * @return the file name
     */
    public static String getFileName(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        return FilenameUtils.getName(FilenameUtils.separatorsToUnix(path.trim()));
    }

    /**
     * Returns the base name (file name without extension) from the given path string.
     *
     * @param path
     *            the path string
     * @return the base name
     */
    public static String getBaseName(String path) {
        String fileName = getFileName(path);
        if (fileName.isEmpty() || fileName.lastIndexOf('.') <= 0) {
            return fileName;
        }
        return FilenameUtils.removeExtension(fileName);
    }

    /**
     * Returns the lowercase file extension from the given path string.
     *
     * @param path
     *            the path string
     * @param allowLeadingDotFile
     *            whether to treat leading dots as part of the filename
     * @return the lowercase extension, or empty string if none
     */
    public static String getLowerExtension(String path, boolean allowLeadingDotFile) {
        String fileName = getFileName(path);
        if (fileName.isEmpty() || (!allowLeadingDotFile && StringUtils.isEmpty(FilenameUtils.getBaseName(fileName)))) {
            return "";
        }
        return FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
    }

    /**
     * Sanitizes a string into a URL-friendly slug (lowercase alphanumeric and hyphens).
     *
     * @param raw
     *            the raw string
     * @param maxLength
     *            the maximum length of the slug
     * @param fallback
     *            the fallback slug if the result is empty
     * @return the sanitized slug
     */
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

    /**
     * Formats an ISO-8601 timestamp string for display.
     *
     * @param rawTimestamp
     *            the ISO timestamp string
     * @param maxLength
     *            the maximum length for the result
     * @param emptyFallback
     *            the fallback string if the timestamp is blank
     * @return the formatted display timestamp
     */
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

    /**
     * Prepares a unique archive file target based on a timestamp and title.
     *
     * @param archiveDir
     *            the archive directory
     * @param isoTimestamp
     *            the ISO timestamp
     * @param title
     *            the title for the archive
     * @param maxSlugLength
     *            the maximum slug length
     * @param fallbackSlug
     *            the fallback slug
     * @return the resolved archive file target
     * @throws IOException
     *             if directory creation fails
     */
    public static ArchiveFileTarget prepareArchiveTarget(Path archiveDir, String isoTimestamp, String title,
            int maxSlugLength, String fallbackSlug) throws IOException {
        Files.createDirectories(archiveDir);
        String tsPrefix = formatArchiveTimestamp(isoTimestamp);
        String slug = sanitizeSlug(title, maxSlugLength, fallbackSlug);
        return resolveUniqueArchiveFile(archiveDir, tsPrefix + "-" + slug);
    }

    /**
     * Computes the SHA-256 hash of the given input and returns the first N bytes as a hex string.
     *
     * @param input
     *            the input string
     * @param byteCount
     *            the number of bytes to include in the hex string
     * @return the hex-encoded hash prefix
     */
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

    /**
     * Reads a classpath resource as a UTF-8 string.
     *
     * @param path
     *            the resource path
     * @param anchorClass
     *            the class whose class loader should be used
     * @return the resource content as a string
     * @throws IOException
     *             if reading fails
     */
    @SuppressWarnings("PMD.UseProperClassLoader")
    public static String readClasspathResourceUtf8(String path, Class<?> anchorClass) throws IOException {
        ClassPathResource resource = new ClassPathResource(path, anchorClass.getClassLoader());
        try (InputStream is = resource.getInputStream()) {
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        }
    }

    public static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> lines = content.lines().toList();
        if (lines.stream().allMatch(String::isEmpty)) {
            return List.of(content.split("\\R", -1));
        }
        return lines;
    }
}
