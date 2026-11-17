package org.roxycode.app.ai.services;

/**
 * JEXL-compliant summary of a database table or view.
 */
public record DbTableSummary(String schema, String name, String tableType, String remarks) {

    public String getSchema() {
        return schema;
    }

    public String getName() {
        return name;
    }

    public String getTableType() {
        return tableType;
    }

    public String getRemarks() {
        return remarks;
    }
}
