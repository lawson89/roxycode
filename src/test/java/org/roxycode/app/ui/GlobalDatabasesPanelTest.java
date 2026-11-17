package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.DbService;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.DatabaseType;
import org.roxycode.app.service.CredentialStoreService;
import org.roxycode.app.service.DatabaseConfigService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;

class GlobalDatabasesPanelTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAndDeletesGlobalConnectionThroughPanelWorkflow() throws Exception {
        Path settingsPath = tempDir.resolve("roxy-home").resolve("settings.toml");
        Path projectRoot = Files.createDirectories(tempDir.resolve("repo"));
        TomlMapper tomlMapper = new TomlMapper();
        SettingsService settingsService = new SettingsService(tomlMapper, e -> {
        });
        settingsService.setSettingsPathOverride(settingsPath);
        ProjectService projectService = new ProjectService(settingsService, e -> {
        });
        projectService.setCurrentProjectRoot(projectRoot);

        try (CredentialStoreService credStore = new CredentialStoreService()) {
            DatabaseConfigService configService = new DatabaseConfigService(settingsService, projectService, credStore,
                    tomlMapper, e -> {
                    });
            try (DbService dbService = new DbService(configService, credStore)) {
                GlobalDatabasesPanel[] holder = new GlobalDatabasesPanel[1];
                Edt.runAndWait(() -> holder[0] = new GlobalDatabasesPanel(configService, credStore, dbService));
                GlobalDatabasesPanel panel = holder[0];
                panel.lastAsyncFuture.get(5, TimeUnit.SECONDS);

                Edt.runAndWait(() -> {
                    panel.startNewConnection();
                    panel.displayNameField.setText("Staging H2");
                    panel.typeCombo.setSelectedItem(DatabaseType.H2);
                    panel.urlField.setText("jdbc:h2:mem:ui_test;DB_CLOSE_DELAY=-1");
                    panel.saveCurrentConnection();
                });
                panel.lastAsyncFuture.get(5, TimeUnit.SECONDS);
                SwingTestUtils.flushEdt();

                List<DatabaseConnectionConfig> saved = configService.listGlobalConnections();
                assertEquals(1, saved.size());
                assertEquals("staging-h2", saved.get(0).id());

                Edt.runAndWait(() -> {
                    panel.connectionList.setSelectedIndex(0);
                    panel.promptDeleteCurrent();
                    assertTrue(panel.confirmBar.isPromptVisible());
                    panel.confirmBar.confirm();
                });
                panel.lastAsyncFuture.get(5, TimeUnit.SECONDS);
                SwingTestUtils.flushEdt();

                assertFalse(panel.confirmBar.isPromptVisible());
                assertTrue(configService.listGlobalConnections().isEmpty());
            }
        }
    }
}
