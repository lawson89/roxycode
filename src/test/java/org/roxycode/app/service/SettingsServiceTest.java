package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;

class SettingsServiceTest {

    @TempDir Path tempDir;

    @Test
    void testSaveAndLoadSettings() {
        SettingsService service = new SettingsService();
        Path settingsFile = tempDir.resolve("settings.toml");
        service.setSettingsPathOverride(settingsFile);

        AppSettings settings = service.getSettings();
        settings.setTheme("Darcula");
        settings.setGeminiApiKey("test-api-key");
        service.saveSettings();

        // New service instance to verify persistence
        SettingsService service2 = new SettingsService();
        service2.setSettingsPathOverride(settingsFile);
        assertEquals("Darcula", service2.getSettings().getTheme());
        assertEquals("test-api-key", service2.getSettings().getGeminiApiKey());
        assertTrue(Files.exists(settingsFile));

        // Verify static loadFromDisk helper
        AppSettings staticLoaded =
                SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertEquals("Darcula", staticLoaded.getTheme());
        assertEquals("test-api-key", staticLoaded.getGeminiApiKey());
    }

    @Test
    void testGetRoxyHomeDirDefaultAndOverride() {
        SettingsService service = new SettingsService();
        Path defaultHome = service.getRoxyHomeDir();
        assertNotNull(defaultHome);
        assertTrue(defaultHome.endsWith(".roxycode"));
        assertEquals(SettingsService.getDefaultRoxyHomeDir(), defaultHome);

        Path customSettings = tempDir.resolve("custom-roxy").resolve("settings.toml");
        service.setSettingsPathOverride(customSettings);
        assertEquals(tempDir.resolve("custom-roxy"), service.getRoxyHomeDir());
    }

    @Test
    void testLoadFromDiskFallbacksAndDefaultPath() throws IOException {
        Path defaultPath = SettingsService.getDefaultSettingsPath();
        assertNotNull(defaultPath);
        assertTrue(defaultPath.endsWith(Path.of(".roxycode", "settings.toml")));

        Path missingFile = tempDir.resolve("missing.toml");
        AppSettings fromMissing =
                SettingsService.loadFromDisk(missingFile, AiConfig.SHARED_TOML_MAPPER);
        assertNotNull(fromMissing);
        assertEquals("FlatLaf Light", fromMissing.getTheme());

        Path corruptFile = tempDir.resolve("corrupt.toml");
        Files.writeString(corruptFile, "invalid = [toml");
        AppSettings fromCorrupt = SettingsService.loadFromDisk(corruptFile, null);
        assertNotNull(fromCorrupt);
        assertEquals("FlatLaf Light", fromCorrupt.getTheme());
    }

    @Test
    void testSettingsListener() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        SettingsService service =
                new SettingsService(
                        event -> {
                            if (event instanceof SettingsChangedEvent) {
                                listenerCalled.set(true);
                            }
                        });
        service.setSettingsPathOverride(tempDir.resolve("test_listener.toml"));

        service.saveSettings();
        assertTrue(
                listenerCalled.get(),
                "SettingsChangedEvent should be published when settings are saved");
    }

    @Test
    void testGetAvailableModelsLoadsFromClasspathToml() {
        SettingsService service = new SettingsService();
        var models = service.getAvailableModels();
        assertNotNull(models);
        assertTrue(!models.isEmpty(), "Available models from /models.toml should not be empty");
        assertTrue(models.stream().anyMatch(m -> "gemini-3.5-flash".equals(m.apiName())));
    }
}
