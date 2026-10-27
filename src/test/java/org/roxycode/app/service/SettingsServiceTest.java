package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        SettingsService service = new SettingsService(null, null);
        Path settingsFile = tempDir.resolve("settings.toml");
        service.setSettingsPathOverride(settingsFile);

        AppSettings settings = service.getSettings();
        settings.setTheme("Darcula");
        settings.setGeminiApiKey("test-api-key");
        settings.setGlobalContextFolders(List.of("/tmp/global-a", "/tmp/global-b"));
        service.saveSettings();

        // New service instance to verify persistence
        SettingsService service2 = new SettingsService(null, null);
        service2.setSettingsPathOverride(settingsFile);
        assertEquals("Darcula", service2.getSettings().getTheme());
        assertEquals("test-api-key", service2.getSettings().getGeminiApiKey());
        assertEquals(
                List.of("/tmp/global-a", "/tmp/global-b"),
                service2.getSettings().getGlobalContextFolders());
        assertTrue(Files.exists(settingsFile));

        // Verify static loadFromDisk helper
        AppSettings staticLoaded =
                SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertEquals("Darcula", staticLoaded.getTheme());
        assertEquals("test-api-key", staticLoaded.getGeminiApiKey());
    }

    @Test
    void testGetRoxyHomeDirDefaultAndOverride() {
        SettingsService service = new SettingsService(null, null);
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
                        null,
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
    void testGetAvailableModelsLoadsFromClasspathTomlAndCachesResult() throws Exception {
        SettingsService service = new SettingsService(null, null);
        var models = service.getAvailableModels();
        assertNotNull(models);
        assertTrue(!models.isEmpty(), "Available models from /models.toml should not be empty");
        assertTrue(models.stream().anyMatch(m -> "gemini-3.5-flash".equals(m.apiName())));
        assertSame(
                models, service.getAvailableModels(), "Repeated calls should return cached list");

        // Verify saveSettings on EDT writes asynchronously off the EDT
        Path edtSettingsFile = tempDir.resolve("edt_settings.toml");
        service.setSettingsPathOverride(edtSettingsFile);
        service.getSettings().setTheme("FlatLaf Dark");
        javax.swing.SwingUtilities.invokeAndWait(service::saveSettings);
        service.getLastSaveFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(Files.exists(edtSettingsFile));
        assertEquals(
                "FlatLaf Dark",
                SettingsService.loadFromDisk(edtSettingsFile, AiConfig.SHARED_TOML_MAPPER)
                        .getTheme());
    }

    @Test
    void testConcurrentLazyInitializationReturnsSameInstance() throws Exception {
        SettingsService service = new SettingsService(null, null);
        service.setSettingsPathOverride(tempDir.resolve("concurrent_settings.toml"));

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AppSettings>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return service.getSettings();
                                }));
            }
            ready.await();
            start.countDown();
            AppSettings expected = futures.get(0).get();
            assertNotNull(expected);
            for (Future<AppSettings> future : futures) {
                assertSame(expected, future.get());
            }
        }
    }
}
