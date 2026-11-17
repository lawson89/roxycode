package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import org.roxycode.app.util.PathUtils;

/**
 * Global read-only database connection configuration persisted in {@code ~/.roxycode/databases.toml}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatabaseConnectionConfig(@JsonProperty("id") String id, @JsonProperty("display_name") String displayName,
        @JsonProperty("type") String type, @JsonProperty("url") String url, @JsonProperty("username") String username,
        @JsonProperty("credential_source") String credentialSource, @JsonProperty("password_env") String passwordEnv,
        @JsonProperty("driver_paths") List<String> driverPaths, @JsonProperty("driver_class") String driverClass,
        @JsonProperty("default_schema") String defaultSchema,
        @JsonProperty("query_timeout_seconds") int queryTimeoutSeconds, @JsonProperty("max_rows") int maxRows) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 15;
    public static final int MAX_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_MAX_ROWS = 200;
    public static final int MAX_ROWS_LIMIT = 2000;

    public DatabaseConnectionConfig {
        id = normalizeId(id, displayName);
        displayName = normalizeText(displayName, id);
        type = DatabaseType.fromString(type).getId();
        url = normalizeText(url, "");
        username = normalizeText(username, "");
        credentialSource = CredentialSource.fromString(credentialSource).getId();
        passwordEnv = normalizeText(passwordEnv, "");
        driverPaths = normalizeDriverPaths(driverPaths);
        driverClass = normalizeText(driverClass, "");
        defaultSchema = normalizeText(defaultSchema, "");
        queryTimeoutSeconds = clampInt(queryTimeoutSeconds, DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);
        maxRows = clampInt(maxRows, DEFAULT_MAX_ROWS, 1, MAX_ROWS_LIMIT);
    }

    /**
     * Resolves the {@link DatabaseType} enum for this connection.
     */
    public DatabaseType resolveType() {
        return DatabaseType.fromString(type);
    }

    /**
     * Resolves the {@link CredentialSource} enum for this connection.
     */
    public CredentialSource resolveCredentialSource() {
        return CredentialSource.fromString(credentialSource);
    }

    private static String normalizeId(String rawId, String rawDisplayName) {
        if (rawId != null && !rawId.isBlank()) {
            return PathUtils.sanitizeSlug(rawId, 48, "database");
        }
        if (rawDisplayName != null && !rawDisplayName.isBlank()) {
            return PathUtils.sanitizeSlug(rawDisplayName, 48, "database");
        }
        return "database";
    }

    private static String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static List<String> normalizeDriverPaths(List<String> rawPaths) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String path : rawPaths) {
            if (path != null && !path.isBlank()) {
                String trimmed = path.trim();
                if (!cleaned.contains(trimmed)) {
                    cleaned.add(trimmed);
                }
            }
        }
        return List.copyOf(cleaned);
    }

    private static int clampInt(int value, int defaultValue, int min, int max) {
        if (value <= 0) {
            return defaultValue;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
