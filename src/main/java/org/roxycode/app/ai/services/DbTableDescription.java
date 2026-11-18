package org.roxycode.app.ai.services;

import java.util.List;

/** Structural description of a table including columns, indexes, and foreign keys. */
public record DbTableDescription(String schema, String tableName, String tableType, List<DbColumnInfo> columns,
        List<DbIndexInfo> indexes, List<DbForeignKeyInfo> foreignKeys) {
}