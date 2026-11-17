package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadOnlySqlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM users", "SELECT id, name FROM users WHERE id = 1;",
            "SELECT 'hello;world' AS txt FROM users",
            "WITH active AS (SELECT id FROM users WHERE active = true) SELECT * FROM active",
            "SELECT id FROM a UNION ALL SELECT id FROM b", "EXPLAIN SELECT * FROM users", "SHOW TABLES",
            "PRAGMA table_info(users)", "PRAGMA database_list"})
    void allowsSingleReadOnlyStatements(String sql) {
        String validated = ReadOnlySqlGuard.validateReadOnlySql(sql);
        assertEquals(sql.replaceAll(";+$", "").trim(), validated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "SELECT 1; DROP TABLE users", "SELECT * INTO temp_users FROM users",
            "SELECT * FROM users FOR UPDATE", "SELECT * FROM users FOR SHARE",
            "WITH d AS (DELETE FROM users RETURNING *) SELECT * FROM d", "INSERT INTO users (id) VALUES (1)",
            "UPDATE users SET name = 'x'", "DELETE FROM users", "DROP TABLE users",
            "ALTER TABLE users ADD COLUMN c INT", "TRUNCATE TABLE users", "CALL my_proc()", "SET search_path = public",
            "PRAGMA journal_mode = WAL", "EXPLAIN DELETE FROM users", "SELECT pg_terminate_backend(123)",
            "SELECT lo_export(1, '/tmp/out')"})
    void rejectsNonReadOnlyOrMultiStatementSql(String sql) {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.validateReadOnlySql(sql));
    }

    @Test
    void validateSelectOnlyAcceptsSelectAndRejectsShowOrDelete() {
        assertEquals("SELECT 1", ReadOnlySqlGuard.validateSelectOnly("SELECT 1;"));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.validateSelectOnly("SHOW TABLES"));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.validateSelectOnly("DELETE FROM users"));
    }
}
