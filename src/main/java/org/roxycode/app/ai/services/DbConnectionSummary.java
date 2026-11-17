package org.roxycode.app.ai.services;

/**
 * JEXL-compliant summary of a granted read-only database connection.
 */
public record DbConnectionSummary(String id, String displayName, String type, String defaultSchema, String notes,
        int queryTimeoutSeconds, int maxRows) {

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getType() {
        return type;
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public String getNotes() {
        return notes;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public int getMaxRows() {
        return maxRows;
    }
}
