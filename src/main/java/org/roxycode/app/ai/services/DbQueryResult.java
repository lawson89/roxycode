package org.roxycode.app.ai.services;

import java.util.List;
import java.util.Map;

/** Result of a read-only SQL query or EXPLAIN plan execution. */
public record DbQueryResult(String connectionId, String sql, List<String> columns, List<Map<String, Object>> rows,
        int rowCount, boolean truncated, long durationMs) {
}