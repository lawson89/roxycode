package org.roxycode.app.ai.services;

/** Summary of a database table or view. */
public record DbTableSummary(String schema, String name, String tableType, String remarks) {
}