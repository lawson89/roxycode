package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import com.github.javakeyring.BackendNotSupportedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.RoxyCode;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ThinkingLevel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.context.support.GenericApplicationContext;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testSaveAndLoadSettings() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        SettingsService service = new SettingsService(null, event -> {
            if (event instanceof SettingsChangedEvent) {
                listenerCalled.set(true);
            }
        });

        Path defaultHome = service.getRoxyHomeDir();
        assertNotNull(defaultHome);
        assertTrue(defaultHome.endsWith(".roxycode"));
        assertEquals(SettingsService.getDefaultRoxyHomeDir(), defaultHome);

        Path settingsFile = tempDir.resolve("custom-roxy").resolve("settings.toml");
        service.setSettingsPathOverride(settingsFile);
        assertEquals(tempDir.resolve("custom-roxy"), service.getRoxyHomeDir());
        assertFalse(service.hasSettingsFile());
        assertFalse(service.hasConfiguredCredentials());

        service.getSettings().setGeminiApiKey("   ");
        service.saveSettings();
        assertTrue(service.hasSettingsFile());
        assertFalse(service.hasConfiguredCredentials());

        AppSettings settings = service.getSettings();
        settings.setTheme("Darcula");
        settings.setGeminiApiKey("test-api-key");
        settings.setAgentProfile("max");
        settings.setGlobalContextFolders(List.of("/tmp/global-a", "/tmp/global-b"));
        service.saveSettings();

        assertTrue(listenerCalled.get(), "SettingsChangedEvent should be published when settings are saved");
        assertTrue(service.hasSettingsFile());
        assertTrue(service.hasConfiguredCredentials());
        assertEquals(AgentProfile.MAX, SettingsService.resolveGlobalAgentProfile(service));
        assertEquals("gemini-4-carbon", SettingsService.resolveGlobalGeminiModel(service, WorkflowPhase.PLAN));
        assertEquals("gemini-4-hydrogen", SettingsService.resolveGlobalGeminiModel(service, WorkflowPhase.CODE));

        SettingsService service2 = new SettingsService(null, null);
        service2.setSettingsPathOverride(settingsFile);
        assertEquals("Darcula", service2.getSettings().getTheme());
        assertEquals("test-api-key", service2.getSettings().getGeminiApiKey());
        assertEquals("MAX", service2.getSettings().getAgentProfile());
        assertEquals(AgentProfile.MAX, SettingsService.resolveGlobalAgentProfile(service2));

        assertEquals(List.of("/tmp/global-a", "/tmp/global-b"), service2.getSettings().getGlobalContextFolders());
        assertTrue(Files.exists(settingsFile));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(settingsFile));
            } catch (IOException e) {
                fail("Failed to read POSIX permissions: " + e.getMessage());
            }
        }

        AppSettings staticLoaded = SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertEquals("Darcula", staticLoaded.getTheme());
        assertEquals("test-api-key", staticLoaded.getGeminiApiKey());
        assertNull(staticLoaded.getWindowX());
        assertNull(staticLoaded.getWindowY());
        assertEquals(AppSettings.DEFAULT_WINDOW_WIDTH, staticLoaded.getWindowWidth());
        assertEquals(AppSettings.DEFAULT_WINDOW_HEIGHT, staticLoaded.getWindowHeight());
        assertFalse(staticLoaded.isWindowMaximized());

        settings.setWindowX(120);
        settings.setWindowY(80);
        settings.setWindowWidth(1440);
        settings.setWindowHeight(960);
        settings.setWindowMaximized(true);
        service.saveSettingsSync();

        AppSettings syncedLoaded = SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertEquals(120, syncedLoaded.getWindowX());
        assertEquals(80, syncedLoaded.getWindowY());
        assertEquals(1440, syncedLoaded.getWindowWidth());
        assertEquals(960, syncedLoaded.getWindowHeight());
        assertTrue(syncedLoaded.isWindowMaximized());
    }

    @Test
    void testVertexAiSettingsPersistenceCredentialReadinessAndStartupInitializer() {
        SettingsService service = new SettingsService(null, null);
        Path settingsFile = tempDir.resolve("vertex-roxy").resolve("settings.toml");
        service.setSettingsPathOverride(settingsFile);

        AppSettings settings = service.getSettings();
        assertFalse(settings.isVertexAiEnabled());
        assertEquals("", settings.getVertexProjectId());
        assertEquals(AppSettings.DEFAULT_VERTEX_LOCATION, settings.getVertexLocation());
        assertEquals("", settings.getVertexCredentialsPath());
        assertFalse(service.hasConfiguredCredentials());
        assertFalse(SettingsService.hasConfiguredCredentials(null));

        settings.setVertexAiEnabled(true);
        assertFalse(service.hasConfiguredCredentials(), "Vertex AI mode without projectId should not be ready");

        settings.setVertexProjectId("my-gcp-project");
        settings.setVertexLocation("us-central1");
        Path credsFile = tempDir.resolve("sa-key.json");
        settings.setVertexCredentialsPath(credsFile.toString());
        assertTrue(service.hasConfiguredCredentials());

        settings.setVertexLocation("   ");
        assertEquals(AppSettings.DEFAULT_VERTEX_LOCATION, settings.getVertexLocation());
        assertTrue(service.hasConfiguredCredentials());

        service.saveSettingsSync();

        AppSettings reloaded = SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
        assertTrue(reloaded.isVertexAiEnabled());
        assertEquals("my-gcp-project", reloaded.getVertexProjectId());
        assertEquals("global", reloaded.getVertexLocation());
        assertEquals(credsFile.toString(), reloaded.getVertexCredentialsPath());

        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            RoxyCode.createSettingsInitializer(reloaded).initialize(ctx);
            assertEquals("true", ctx.getEnvironment().getProperty("spring.ai.google.genai.vertex-ai"));
            assertEquals("my-gcp-project", ctx.getEnvironment().getProperty("spring.ai.google.genai.project-id"));
            assertEquals("global", ctx.getEnvironment().getProperty("spring.ai.google.genai.location"));
            assertEquals(credsFile.toUri().toString(),
                    ctx.getEnvironment().getProperty("spring.ai.google.genai.credentials-uri"));
        }
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
        assertEquals("NORMAL", fromCorrupt.getAgentProfile());
        assertEquals("ADAPTIVE", fromCorrupt.getThinkingLevel());
        assertEquals(AgentProfile.NORMAL, SettingsService.resolveGlobalAgentProfile(null));

        Path legacyToml = Files.writeString(tempDir.resolve("legacy.toml"), """
                theme = "FlatLaf Dark"
                geminiModel = "gemini-3.5-flash"
                """);
        AppSettings fromLegacy = SettingsService.loadFromDisk(legacyToml, AiConfig.SHARED_TOML_MAPPER);
        assertEquals("NORMAL", fromLegacy.getAgentProfile());
        assertEquals("ADAPTIVE", fromLegacy.getThinkingLevel());

        assertEquals(AgentProfile.NORMAL, AgentProfile.fromString(null));
        assertEquals(AgentProfile.NORMAL, AgentProfile.fromString("  "));
        assertEquals(AgentProfile.NORMAL, AgentProfile.fromString("unknown-profile"));
        assertEquals(AgentProfile.MAX, AgentProfile.fromString("max"));
        assertEquals(AgentProfile.MAX, AgentProfile.fromString("gemini-4-carbon"));
        assertEquals(AgentProfile.NORMAL, AgentProfile.fromString("Normal"));
        assertEquals(AgentProfile.NORMAL, AgentProfile.fromString("gemini-4-argon"));
        assertEquals(AgentProfile.SPEED, AgentProfile.fromString("SPEED"));
        assertEquals(AgentProfile.SPEED, AgentProfile.fromString("gemini-4-hydrogen"));
        assertEquals(AgentProfile.SPEED, AgentProfile.fromString("gemini-3.8-flash"));

        assertEquals(ThinkingLevel.DEFAULT, ThinkingLevel.fromString(null));
        assertEquals(ThinkingLevel.DEFAULT, ThinkingLevel.fromString("  "));
        assertEquals(ThinkingLevel.DEFAULT, ThinkingLevel.fromString("unknown-level"));
        assertEquals(ThinkingLevel.ADAPTIVE, ThinkingLevel.fromString("ADAPTIVE"));
        assertEquals(ThinkingLevel.ADAPTIVE, ThinkingLevel.fromString("Adaptive (Phase-Aware)"));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromString("NONE"));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromString("minimal"));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromString("low"));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString("Medium"));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromString("HIGH"));

        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, null, false, false, false));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, null, false, true, false));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, null, true, true, false));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-carbon", false, false, false));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-carbon", false, true, false));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-carbon", false, true, true));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-carbon", true, true, true));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-argon", false, false, false));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-argon", false, true, false));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-argon", true, true, false));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-hydrogen", false, false, false));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.PLAN, "gemini-4-hydrogen", false, true, false));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.CODE, null, true, true, false));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.CODE, null, true, true, true));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.REVIEW, null, true, false, false));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.EXPLORE, null, false, false, false));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.ADAPTIVE
                .resolveSubTurnLevel(org.roxycode.app.ai.WorkflowPhase.EXPLORE, null, false, true, false));

        GoogleGenAiChatOptions.Builder defaultBuilder = GoogleGenAiChatOptions.builder().model("gemini-3.5-flash");
        ThinkingLevel.DEFAULT.applyTo(defaultBuilder);
        assertNull(defaultBuilder.build().getThinkingLevel());

        GoogleGenAiChatOptions.Builder adaptiveBuilder = GoogleGenAiChatOptions.builder().model("gemini-3.5-flash");
        ThinkingLevel.ADAPTIVE.applyTo(adaptiveBuilder);
        assertNull(adaptiveBuilder.build().getThinkingLevel());

        GoogleGenAiChatOptions.Builder highBuilder = GoogleGenAiChatOptions.builder().model("gemini-3.5-flash");
        ThinkingLevel.HIGH.applyTo(highBuilder);
        assertEquals(GoogleGenAiThinkingLevel.HIGH, highBuilder.build().getThinkingLevel());
    }

    @Test
    void testGetAvailableModelsLoadsFromClasspathTomlAndCachesResult() throws Exception {
        SettingsService service = new SettingsService(null, null);
        List<org.roxycode.app.model.GeminiModelConfig> models = service.getAvailableModels();
        assertNotNull(models);
        assertFalse(models.isEmpty(), "Available models from /models.toml should not be empty");
        assertTrue(models.stream().anyMatch(m -> "gemini-4-carbon".equals(m.apiName())));
        assertTrue(models.stream().anyMatch(m -> "gemini-4-argon".equals(m.apiName())));
        assertTrue(models.stream().anyMatch(m -> "gemini-4-hydrogen".equals(m.apiName())));
        assertTrue(models.stream().anyMatch(m -> "gemini-3.8-flash".equals(m.apiName())));
        assertSame(models, service.getAvailableModels(), "Repeated calls should return cached list");

        org.roxycode.app.model.GeminiModelConfig flashCfg = service.resolveModelConfig("gemini-3.8-flash");
        assertEquals(1_048_576, flashCfg.contextWindowTokens());
        assertEquals(0.75, flashCfg.inputPricePerMillion(), 1e-6);
        assertEquals(0.075, flashCfg.cachedInputPricePerMillion(), 1e-6);
        assertEquals(3.75, flashCfg.outputPricePerMillion(), 1e-6);

        org.roxycode.app.model.GeminiModelConfig proCfg = service.resolveModelConfig("gemini-4-argon");
        assertEquals(2.00, proCfg.inputPricePerMillion(), 1e-6);
        assertEquals(0.10, proCfg.cachedInputPricePerMillion(), 1e-6);
        assertEquals(10.00, proCfg.outputPricePerMillion(), 1e-6);

        org.roxycode.app.model.GeminiModelConfig hydrogenCfg = service.resolveModelConfig("gemini-4-hydrogen");
        assertEquals(1.50, hydrogenCfg.inputPricePerMillion(), 1e-6);
        assertEquals(0.15, hydrogenCfg.cachedInputPricePerMillion(), 1e-6);
        assertEquals(7.50, hydrogenCfg.outputPricePerMillion(), 1e-6);
        assertEquals(0.004, proCfg.calculateUncachedInputCostUsd(2_000), 1e-7);
        assertEquals(0.001, proCfg.calculateCachedInputCostUsd(10_000), 1e-7);
        assertEquals(0.006, proCfg.calculateOutputCostUsd(600), 1e-7);

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

    @Test
    void testGeminiApiKeyKeyringStorageAutoMigrationAndFallback() throws IOException {
        String prevTestMode = System.getProperty("roxycode.testMode");
        System.clearProperty("roxycode.testMode");
        Map<String, String> keyringStore = new HashMap<>();
        CredentialStoreService.KeyringBackend persistentBackend = new CredentialStoreService.KeyringBackend() {
            @Override
            public String getPassword(String service, String account) {
                return keyringStore.get(service + ":" + account);
            }

            @Override
            public void setPassword(String service, String account, String password) {
                keyringStore.put(service + ":" + account, password);
            }

            @Override
            public void deletePassword(String service, String account) {
                keyringStore.remove(service + ":" + account);
            }

            @Override
            public boolean isPersistent() {
                return true;
            }
        };

        try (CredentialStoreService keyringCreds = new CredentialStoreService(() -> persistentBackend)) {
            Path settingsFile = tempDir.resolve("keyring-roxy").resolve("settings.toml");
            SettingsService service = new SettingsService(AiConfig.SHARED_TOML_MAPPER, null, keyringCreds);
            service.setSettingsPathOverride(settingsFile);
            assertTrue(service.isNativeKeyringAvailable());

            service.getSettings().setGeminiApiKey("AIza-keyring-secret");
            service.saveSettingsSync();

            assertEquals("AIza-keyring-secret", service.getSettings().getGeminiApiKey());
            assertEquals("AIza-keyring-secret", keyringCreds.loadGeminiApiKey());

            AppSettings rawOnDisk = SettingsService.loadFromDisk(settingsFile, AiConfig.SHARED_TOML_MAPPER);
            assertEquals("", rawOnDisk.getGeminiApiKey());

            SettingsService reloaded = new SettingsService(AiConfig.SHARED_TOML_MAPPER, null, keyringCreds);
            reloaded.setSettingsPathOverride(settingsFile);
            assertEquals("AIza-keyring-secret", reloaded.getSettings().getGeminiApiKey());
            assertTrue(reloaded.hasConfiguredCredentials());

            // Auto-migrate legacy plaintext geminiApiKey from settings.toml into keyring and scrub disk file
            keyringStore.clear();
            Path legacyFile = tempDir.resolve("legacy-migration").resolve("settings.toml");
            Files.createDirectories(legacyFile.getParent());
            Files.writeString(legacyFile, """
                    theme = "FlatLaf Dark"
                    geminiApiKey = "AIza-legacy-plaintext"
                    """);

            SettingsService migratingService = new SettingsService(AiConfig.SHARED_TOML_MAPPER, null, keyringCreds);
            migratingService.setSettingsPathOverride(legacyFile);
            assertEquals("AIza-legacy-plaintext", migratingService.getSettings().getGeminiApiKey());
            assertEquals("AIza-legacy-plaintext", keyringCreds.loadGeminiApiKey());
            assertEquals("", SettingsService.loadFromDisk(legacyFile, AiConfig.SHARED_TOML_MAPPER).getGeminiApiKey());
        } finally {
            if (prevTestMode != null) {
                System.setProperty("roxycode.testMode", prevTestMode);
            } else {
                System.clearProperty("roxycode.testMode");
            }
        }

        // Fallback to settings.toml when native keyring is unavailable
        System.clearProperty("roxycode.testMode");
        try (CredentialStoreService unavailableCreds = new CredentialStoreService(() -> {
            throw new BackendNotSupportedException("headless");
        })) {
            Path fallbackFile = tempDir.resolve("fallback-roxy").resolve("settings.toml");
            SettingsService fallbackService = new SettingsService(AiConfig.SHARED_TOML_MAPPER, null, unavailableCreds);
            fallbackService.setSettingsPathOverride(fallbackFile);
            assertFalse(fallbackService.isNativeKeyringAvailable());

            fallbackService.getSettings().setGeminiApiKey("AIza-fallback-secret");
            fallbackService.saveSettingsSync();

            AppSettings rawFallback = SettingsService.loadFromDisk(fallbackFile, AiConfig.SHARED_TOML_MAPPER);
            assertEquals("AIza-fallback-secret", rawFallback.getGeminiApiKey());
        } finally {
            if (prevTestMode != null) {
                System.setProperty("roxycode.testMode", prevTestMode);
            } else {
                System.clearProperty("roxycode.testMode");
            }
        }
    }
}
