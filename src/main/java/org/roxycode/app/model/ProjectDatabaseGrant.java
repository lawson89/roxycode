package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

/**
 * Per-project read-only database access grant persisted in {@code <projectDataDir>/databases.toml}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectDatabaseGrant(@JsonProperty("connection_id") String connectionId,
        @JsonProperty("default_schema") String defaultSchema, @JsonProperty("notes") String notes) {

    public ProjectDatabaseGrant {
        connectionId = StringUtils.trimToEmpty(connectionId);
        defaultSchema = StringUtils.trimToEmpty(defaultSchema);
        notes = StringUtils.trimToEmpty(notes);
    }
}
