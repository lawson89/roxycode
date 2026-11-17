package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.events.DatabaseConfigChangedEvent;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.ProjectDatabaseGrant;
import org.roxycode.app.service.DatabaseConfigService.ResolvedDatabaseConnection;

class DatabaseConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsGlobalAndProjectDatabasesWithoutLeakingPasswordsAndFiltersDanglingGrants() throws Exception {
        Path settingsPath = tempDir.resolve("roxy-home").resolve("settings.toml");
        Path projectRoot = tempDir.resolve("repo");
        Files.createDirectories(projectRoot);

        TomlMapper tomlMapper = new TomlMapper();
        List<DatabaseConfigChangedEvent> events = new ArrayList<>();
        SettingsService settingsService = new SettingsService(tomlMapper, e -> {
        });
        settingsService.setSettingsPathOverride(settingsPath);
        ProjectService projectService = new ProjectService(settingsService, e -> {
        });
        projectService.setCurrentProjectRoot(projectRoot);

        try (CredentialStoreService credStore = new CredentialStoreService()) {
            DatabaseConfigService service = new DatabaseConfigService(settingsService, projectService, credStore,
                    tomlMapper, event -> {
                        if (event instanceof DatabaseConfigChangedEvent dbEvent) {
                            events.add(dbEvent);
                        }
                    });

            DatabaseConnectionConfig pg = new DatabaseConnectionConfig("qa-pg", "QA Postgres",
                    "Core QA Postgres database", "postgresql", "jdbc:postgresql://localhost:5432/qa", "ro_user",
                    "keyring", "", List.of(), "", "public", 20, 150);
            service.saveGlobalConnection(pg, "super-secret-pw", false);

            Path globalToml = tempDir.resolve("roxy-home").resolve("databases.toml");
            assertTrue(Files.exists(globalToml));
            String rawGlobalToml = Files.readString(globalToml);
            assertFalse(rawGlobalToml.contains("super-secret-pw"));
            assertTrue(rawGlobalToml.contains("Core QA Postgres database"));
            assertTrue(credStore.hasStoredPassword("qa-pg"));

            service.saveProjectGrants(List.of(new ProjectDatabaseGrant("qa-pg", "billing", "Read-only billing tables"),
                    new ProjectDatabaseGrant("deleted-db", "other", "Dangling grant")));

            List<ResolvedDatabaseConnection> active = service.listActiveProjectConnections();
            assertEquals(1, active.size());
            assertEquals("qa-pg", active.get(0).config().id());
            assertEquals("Core QA Postgres database", active.get(0).config().description());
            assertEquals("billing", active.get(0).effectiveDefaultSchema());
            assertEquals("Read-only billing tables", active.get(0).notes());

            assertTrue(service.deleteGlobalConnection("qa-pg"));
            assertFalse(credStore.hasStoredPassword("qa-pg"));
            assertTrue(service.listActiveProjectConnections().isEmpty());
            assertEquals(3, events.size());
        }
    }
}
