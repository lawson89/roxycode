package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testSaveAndLoadSettings() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        SettingsService service = new SettingsService(null, event -> {
            if (event instanceof SettingsChangedEvent)
                listenerCalled.set(true);
        });

        Path defaultHome = service.getRoxyHomeDir();
        assertNotNull(defaultHome);
        assertTrue(defaultHome.endsWith(".roxycode"));
        assertEquals(SettingsService.getDefaultRoxyHomeDir(), defaultHome);

        Path settingsFile = tempDir.resolve("custom-roxy").resolve("settings.toml");
        service.setSettingsPathOverride(settingsFile);
        assertEquals(tempDir.resolve("custom-roxy"), service.getRoxyHomeDir());

        AppSettings settings = service.getSettings();
        settings.setTheme("Darcula");
        settings.setGeminiApiKey("test-api-key");
        settings.setLogToolCalls(true);
        settings.setGlobalContextFolders(List.of("/tmp/global-a", "/tmp/global-b"));
        service.saveSettings();

        assertTrue(listenerCalled.get(), "SettingsChangedEvent should be published when settings are saved");

        SettingsService service2 = new SettingsService(null, null);
        service2.setSettingsPathOverride(settingsFile);
        assertEquals("Darcula", service2.getSettings().getTheme());
        assertEquals("test-api-key", service2.getSettings().getGeminiApiKey());
        assertTrue(service2.getSettings().isLogToolCalls());
        assertEquals(List.of("/tmp/global-a", "/tmp/global-b"), service2.getSettings().getGlobalContextFolders());
        assertTrue(Files.exists(settingsFile));

        AppSettings staticLoaded = SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertEquals("Darcula", staticLoaded.getTheme());
        assertEquals("test-api-key", staticLoaded.getGeminiApiKey());
    }

    @Test
    void testLoadFromDiskFallbacksAndDefaultPath() throws IOException {
        Path defaultPath = SettingsService.getDefaultSettingsPath();
        assertNotNull(defaultPath);
        assertTrue(defaultPath.endsWith(Path.of(".roxycode", "settings.toml")));

        AppSettings fromMissing = SettingsService.loadFromDisk(tempDir.resolve("missing.toml"),
                AiConfig.SHARED_TOML_MAPPER);
        assertEquals("FlatLaf Light", fromMissing.getTheme());

        Path corruptFile = Files.writeString(tempDir.resolve("corrupt.toml"), "invalid = [toml");
        AppSettings fromCorrupt = SettingsService.loadFromDisk(corruptFile, null);
        assertEquals("FlatLaf Light", fromCorrupt.getTheme());
    }

    @Test
    void testGetAvailableModelsLoadsFromClasspathTomlAndCachesResult() throws Exception {
        SettingsService service = new SettingsService(null, null);
        var models = service.getAvailableModels();
        assertNotNull(models);
        assertFalse(models.isEmpty(), "Available models from /models.toml should not be empty");
        assertTrue(models.stream().anyMatch(m -> "gemini-3.5-flash".equals(m.apiName())));
        assertSame(models, service.getAvailableModels(), "Repeated calls should return cached list");

        Path edtSettingsFile = tempDir.resolve("edt_settings.toml");
        service.setSettingsPathOverride(edtSettingsFile);
        service.getSettings().setTheme("FlatLaf Dark");
        javax.swing.SwingUtilities.invokeAndWait(service::saveSettings);
        service.getLastSaveFuture().get(5, TimeUnit.SECONDS);
        assertTrue(Files.exists(edtSettingsFile));
        assertEquals("FlatLaf Dark",
                SettingsService.loadFromDisk(edtSettingsFile, AiConfig.SHARED_TOML_MAPPER).getTheme());
    }

    @Test
    void testRapidEdtSaveSettingsSnapshotsAndSerializesWritesInOrder() throws Exception {
        List<String> writtenThemes = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);

        com.fasterxml.jackson.dataformat.toml.TomlMapper spyMapper = new com.fasterxml.jackson.dataformat.toml.TomlMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public void writeValue(java.io.File resultFile, Object value) throws IOException {
                if (value instanceof AppSettings appSettings) {
                    if ("Theme-1".equals(appSettings.getTheme()) && firstWriteEntered.getCount() > 0) {
                        firstWriteEntered.countDown();
                        try {
                            releaseFirstWrite.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    writtenThemes.add(appSettings.getTheme());
                }
                AiConfig.SHARED_TOML_MAPPER.writeValue(resultFile, value);
            }
        };

        SettingsService service = new SettingsService(spyMapper, null);
        Path settingsFile = tempDir.resolve("ordered_edt_settings.toml");
        service.setSettingsPathOverride(settingsFile);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            AppSettings live = service.getSettings();
            live.setTheme("Theme-1");
            live.setGlobalContextFolders(List.of("/folder/1"));
            service.saveSettings();
        });

        assertTrue(firstWriteEntered.await(5, TimeUnit.SECONDS));

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            AppSettings live = service.getSettings();
            live.setTheme("Theme-2");
            live.setGlobalContextFolders(List.of("/folder/1", "/folder/2"));
            service.saveSettings();

            live.setTheme("Theme-3");
            live.setGlobalContextFolders(List.of("/folder/1", "/folder/2", "/folder/3"));
            service.saveSettings();

            // Mutate live settings after saveSettings() without saving to verify defensive snapshot isolation
            live.setTheme("Unsaved-Mutation");
            live.setGlobalContextFolders(List.of("/unsaved"));
        });

        releaseFirstWrite.countDown();
        service.getLastSaveFuture().get(5, TimeUnit.SECONDS);

        assertEquals(List.of("Theme-1", "Theme-2", "Theme-3"), writtenThemes);
        AppSettings diskSettings = SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertEquals("Theme-3", diskSettings.getTheme());
        assertEquals(List.of("/folder/1", "/folder/2", "/folder/3"), diskSettings.getGlobalContextFolders());
    }
}
