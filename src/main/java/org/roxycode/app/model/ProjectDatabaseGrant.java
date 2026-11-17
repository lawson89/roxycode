package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-project read-only database access grant persisted in {@code <projectDataDir>/databases.toml}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectDatabaseGrant(@JsonProperty("connection_id") String connectionId,
        @JsonProperty("default_schema") String defaultSchema, @JsonProperty("notes") String notes) {

    public ProjectDatabaseGrant {
        connectionId = normalizeText(connectionId);
        defaultSchema = normalizeText(defaultSchema);
        notes = normalizeText(notes);
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
