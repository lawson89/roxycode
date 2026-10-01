package org.roxycode.app.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.model.AppSettings;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(Files.exists(settingsFile));
    }

    @Test
    void testSettingsListener() {
        SettingsService service = new SettingsService();
        service.setSettingsPathOverride(tempDir.resolve("test_listener.toml"));
        
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        service.addSettingsListener(settings -> {
            listenerCalled.set(true);
        });
        
        service.saveSettings();
        assertTrue(listenerCalled.get(), "Listener should be called when settings are saved");
    }
}
