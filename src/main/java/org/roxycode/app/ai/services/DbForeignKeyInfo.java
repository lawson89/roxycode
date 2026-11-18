package org.roxycode.app.ai.services;

/** Metadata for a foreign key constraint. */
public record DbForeignKeyInfo(String name, String columnName, String referencedTable, String referencedColumn) {
}