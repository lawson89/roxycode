package org.roxycode.app.ai.services;

/**
 * JEXL-compliant metadata for a single table column.
 */
public record DbColumnInfo(String name, String typeName, int columnSize, boolean nullable, String defaultValue,
        boolean primaryKey, String remarks) {

    public String getName() {
        return name;
    }

    public String getTypeName() {
        return typeName;
    }

    public int getColumnSize() {
        return columnSize;
    }

    public boolean isNullable() {
        return nullable;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public String getRemarks() {
        return remarks;
    }
}
