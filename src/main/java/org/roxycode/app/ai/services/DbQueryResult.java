package org.roxycode.app.ai.services;

import java.util.List;
import java.util.Map;

/**
 * JEXL-compliant result of a read-only SQL query or EXPLAIN plan execution.
 */
public record DbQueryResult(String connectionId, String sql, List<String> columns, List<Map<String, Object>> rows,
        int rowCount, boolean truncated, long durationMs) {

    public String getConnectionId() {
        return connectionId;
    }

    public String getSql() {
        return sql;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public int getRowCount() {
        return rowCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
