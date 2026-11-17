package org.roxycode.app.ai.services;

/**
 * JEXL-compliant metadata for a foreign key constraint.
 */
public record DbForeignKeyInfo(String name, String columnName, String referencedTable, String referencedColumn) {

    public String getName() {
        return name;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public String getReferencedColumn() {
        return referencedColumn;
    }
}
