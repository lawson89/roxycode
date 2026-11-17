package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.events.DatabaseConfigChangedEvent;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.ProjectDatabaseGrant;
import org.roxycode.app.service.CredentialStoreService;
import org.roxycode.app.service.DatabaseConfigService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class DbServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void inspectsAndQueriesH2ReadOnlyWithTruncationAndGuards() throws Exception {
        String h2Url = "jdbc:h2:mem:db_svc_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection setupConn = DriverManager.getConnection(h2Url, "sa", "");
                Statement stmt = setupConn.createStatement()) {
            stmt.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            stmt.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL, total INT, "
                    + "FOREIGN KEY (customer_id) REFERENCES customers(id))");
            stmt.execute("CREATE INDEX idx_orders_customer ON orders(customer_id)");
            stmt.execute("INSERT INTO customers VALUES (1, 'Alice'), (2, 'Bob')");
            stmt.execute("INSERT INTO orders VALUES (10, 1, 100), (11, 1, 200), (12, 2, 50)");
        }

        TestHarness harness = createHarness();
        try (harness.credStore; DbService dbService = harness.dbService) {
            DatabaseConnectionConfig h2Cfg = new DatabaseConnectionConfig("h2-qa", "H2 QA",
                    "QA orders and customers database", "h2", h2Url, "sa", "none", "", List.of(), "", "PUBLIC", 15, 2);
            harness.configService.saveGlobalConnection(h2Cfg, null, false);
            harness.configService.saveProjectGrants(List.of(new ProjectDatabaseGrant("h2-qa", "PUBLIC", "QA orders")));

            List<DbConnectionSummary> conns = dbService.listConnections();
            assertEquals(1, conns.size());
            assertEquals("h2-qa", conns.get(0).getId());
            assertEquals("QA orders and customers database", conns.get(0).getDescription());

            List<String> schemas = dbService.listSchemas("h2-qa");
            assertTrue(schemas.contains("PUBLIC"));

            List<DbTableSummary> tables = dbService.listTables("h2-qa");
            assertTrue(tables.stream().anyMatch(t -> "ORDERS".equalsIgnoreCase(t.getName())));

            DbTableDescription desc = dbService.describeTable("h2-qa", "ORDERS");
            assertEquals(3, desc.getColumns().size());
            assertTrue(
                    desc.getColumns().stream().anyMatch(c -> "ID".equalsIgnoreCase(c.getName()) && c.isPrimaryKey()));
            assertFalse(desc.getForeignKeys().isEmpty());
            assertFalse(desc.getIndexes().isEmpty());

            DbQueryResult result = dbService.query("h2-qa", "SELECT id, total FROM orders ORDER BY id");
            assertEquals(2, result.getRowCount());
            assertTrue(result.isTruncated());

            DbQueryResult explain = dbService.explain("h2-qa", "SELECT * FROM orders WHERE customer_id = 1");
            assertFalse(explain.getRows().isEmpty());

            assertThrows(IllegalArgumentException.class, () -> dbService.query("h2-qa", "DELETE FROM orders"));
            assertThrows(IllegalArgumentException.class, () -> dbService.listTables("ungranted-db"));

            DbTestResult testRes = dbService.testConnection(h2Cfg, null);
            assertTrue(testRes.isSuccess());
            assertTrue(testRes.getServerSummary().toUpperCase().contains("H2"));

            dbService.onDatabaseConfigChanged(new DatabaseConfigChangedEvent(false));
        }
    }

    @Test
    void enforcesSqliteReadOnlyOpenModeAndExpandsCustomJarPaths() throws Exception {
        Path sqliteFile = tempDir.resolve("sample.db");
        String sqliteUrl = "jdbc:sqlite:" + sqliteFile;
        try (Connection setupConn = DriverManager.getConnection(sqliteUrl);
                Statement stmt = setupConn.createStatement()) {
            stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, label TEXT)");
            stmt.execute("INSERT INTO items VALUES (1, 'Widget')");
        }

        TestHarness harness = createHarness();
        try (harness.credStore; DbService dbService = harness.dbService) {
            DatabaseConnectionConfig sqliteCfg = new DatabaseConnectionConfig("local-sqlite", "Local SQLite", "sqlite",
                    sqliteUrl, "", "none", "", List.of(), "", "", 15, 50);
            harness.configService.saveGlobalConnection(sqliteCfg, null, false);
            harness.configService.saveProjectGrants(List.of(new ProjectDatabaseGrant("local-sqlite", "", "")));

            DbQueryResult qr = dbService.query("local-sqlite", "SELECT id, label FROM items");
            assertEquals(1, qr.getRowCount());
            assertEquals("Widget", qr.getRows().get(0).get("label"));

            DbQueryResult pragmaRes = dbService.query("local-sqlite", "PRAGMA table_info(items)");
            assertEquals(2, pragmaRes.getRowCount());

            Path missingSqlite = tempDir.resolve("does-not-exist.db");
            DatabaseConnectionConfig missingCfg = new DatabaseConnectionConfig("missing-sqlite", "Missing", "sqlite",
                    "jdbc:sqlite:" + missingSqlite, "", "none", "", List.of(), "", "", 15, 50);
            DbTestResult missingTest = dbService.testConnection(missingCfg, null);
            assertFalse(missingTest.isSuccess());
            assertTrue(missingTest.getMessage().startsWith("Connection failed:"));
            assertFalse(Files.exists(missingSqlite));

            String formatted = DbService.formatConnectionFailureMessage(
                    new RuntimeException("outer", new java.sql.SQLException("Invalid password", "28P01")));
            assertTrue(formatted.contains("Invalid password"));
            assertTrue(formatted.contains("SQLState: 28P01"));
        }

        Path jarDir = Files.createDirectories(tempDir.resolve("oracle-jars"));
        Path jarA = Files.writeString(jarDir.resolve("ojdbc11.jar"), "stub");
        Path jarB = Files.writeString(jarDir.resolve("orai18n.jar"), "stub");
        Files.writeString(jarDir.resolve("readme.txt"), "ignored");
        List<Path> expanded = DbService.expandDriverJars(List.of(jarDir.toString(), jarA.toString()));
        assertEquals(List.of(jarA.toAbsolutePath().normalize(), jarB.toAbsolutePath().normalize()), expanded);
    }

    private record TestHarness(CredentialStoreService credStore, DatabaseConfigService configService,
            DbService dbService) {
    }

    private TestHarness createHarness() throws Exception {
        Path settingsPath = tempDir.resolve("roxy-home-" + System.nanoTime()).resolve("settings.toml");
        Path projectRoot = Files.createDirectories(tempDir.resolve("project-" + System.nanoTime()));
        TomlMapper tomlMapper = new TomlMapper();
        SettingsService settingsService = new SettingsService(tomlMapper, e -> {
        });
        settingsService.setSettingsPathOverride(settingsPath);
        ProjectService projectService = new ProjectService(settingsService, e -> {
        });
        projectService.setCurrentProjectRoot(projectRoot);
        CredentialStoreService credStore = new CredentialStoreService();
        DatabaseConfigService configService = new DatabaseConfigService(settingsService, projectService, credStore,
                tomlMapper, e -> {
                });
        DbService dbService = new DbService(configService, credStore);
        return new TestHarness(credStore, configService, dbService);
    }
}
