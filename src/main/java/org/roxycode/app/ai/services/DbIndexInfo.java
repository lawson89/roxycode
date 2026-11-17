package org.roxycode.app.ai.services;

import java.util.List;

/**
 * JEXL-compliant metadata for a table index.
 */
public record DbIndexInfo(String name, boolean unique, List<String> columns) {

    public String getName() {
        return name;
    }

    public boolean isUnique() {
        return unique;
    }

    public List<String> getColumns() {
        return columns;
    }
}
