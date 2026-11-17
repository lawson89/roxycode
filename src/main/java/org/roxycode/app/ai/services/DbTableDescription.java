package org.roxycode.app.ai.services;

import java.util.List;

/**
 * JEXL-compliant structural description of a table including columns, indexes, and foreign keys.
 */
public record DbTableDescription(String schema, String tableName, String tableType, List<DbColumnInfo> columns,
        List<DbIndexInfo> indexes, List<DbForeignKeyInfo> foreignKeys) {

    public String getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }

    public String getTableType() {
        return tableType;
    }

    public List<DbColumnInfo> getColumns() {
        return columns;
    }

    public List<DbIndexInfo> getIndexes() {
        return indexes;
    }

    public List<DbForeignKeyInfo> getForeignKeys() {
        return foreignKeys;
    }
}
