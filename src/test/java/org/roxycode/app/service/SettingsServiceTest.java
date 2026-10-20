package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
