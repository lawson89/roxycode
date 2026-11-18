package org.roxycode.app.ai.services;

/** Metadata for a single table column. */
public record DbColumnInfo(String name, String typeName, int columnSize, boolean nullable, String defaultValue,
        boolean primaryKey, String remarks) {
}