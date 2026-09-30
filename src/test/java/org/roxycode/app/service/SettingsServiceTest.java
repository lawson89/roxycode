package org.roxycode.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.model.AppSettings;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

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
    }
}