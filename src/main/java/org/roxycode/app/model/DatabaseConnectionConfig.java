package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.util.PathUtils;

/** Global read-only database connection configuration persisted in {@code ~/.roxycode/databases.toml}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatabaseConnectionConfig(@JsonProperty("id") String id, @JsonProperty("display_name") String displayName,
        @JsonProperty("description") String description, @JsonProperty("type") String type,
        @JsonProperty("url") String url, @JsonProperty("username") String username,
        @JsonProperty("credential_source") String credentialSource, @JsonProperty("password_env") String passwordEnv,
        @JsonProperty("driver_paths") List<String> driverPaths, @JsonProperty("driver_class") String driverClass,
        @JsonProperty("default_schema") String defaultSchema,
        @JsonProperty("query_timeout_seconds") int queryTimeoutSeconds, @JsonProperty("max_rows") int maxRows) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 15;
    public static final int MAX_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_MAX_ROWS = 200;
    public static final int MAX_ROWS_LIMIT = 2000;

    public DatabaseConnectionConfig {
        String slugSource = StringUtils.defaultIfBlank(id, displayName);
        id = PathUtils.sanitizeSlug(StringUtils.defaultString(slugSource), 48, "database");
        displayName = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(displayName), id);
        description = StringUtils.trimToEmpty(description);
        type = DatabaseType.fromString(type).getId();
        url = StringUtils.trimToEmpty(url);
        username = StringUtils.trimToEmpty(username);
        credentialSource = CredentialSource.fromString(credentialSource).getId();
        passwordEnv = StringUtils.trimToEmpty(passwordEnv);
        driverPaths = normalizeDriverPaths(driverPaths);
        driverClass = StringUtils.trimToEmpty(driverClass);
        defaultSchema = StringUtils.trimToEmpty(defaultSchema);
        queryTimeoutSeconds = clampInt(queryTimeoutSeconds, DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);
        maxRows = clampInt(maxRows, DEFAULT_MAX_ROWS, 1, MAX_ROWS_LIMIT);
    }

    /** Backward-compatible constructor without an explicit global description. */
    public DatabaseConnectionConfig(String id, String displayName, String type, String url, String username,
            String credentialSource, String passwordEnv, List<String> driverPaths, String driverClass,
            String defaultSchema, int queryTimeoutSeconds, int maxRows) {
        this(id, displayName, "", type, url, username, credentialSource, passwordEnv, driverPaths, driverClass,
                defaultSchema, queryTimeoutSeconds, maxRows);
    }

    public DatabaseType resolveType() {
        return DatabaseType.fromString(type);
    }

    public CredentialSource resolveCredentialSource() {
        return CredentialSource.fromString(credentialSource);
    }

    private static List<String> normalizeDriverPaths(List<String> rawPaths) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return List.of();
        }
        return rawPaths.stream().filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
    }

    private static int clampInt(int value, int defaultValue, int min, int max) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.clamp(value, min, max);
    }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}