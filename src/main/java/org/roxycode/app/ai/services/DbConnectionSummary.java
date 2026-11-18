package org.roxycode.app.ai.services;

/** Summary of a granted read-only database connection. */
public record DbConnectionSummary(String id, String displayName, String type, String description, String defaultSchema,
        String notes, int queryTimeoutSeconds, int maxRows) {
}