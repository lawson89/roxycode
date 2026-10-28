package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.model.Version;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService.DockerProbe;
import org.roxycode.app.service.SystemToolService.DockerVersionInfo;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;

class SystemToolServiceTest {

    @TempDir
    Path tempDir;

    private static SystemTool assertDockerTool(DockerProbe probe, boolean cliPresent, String expectedStatus,
            boolean expectedAvailable) {
        SystemToolService service = new SystemToolService(probe, () -> cliPresent);
        SystemTool dockerTool = service.detectDockerTool();
        assertEquals("Docker", dockerTool.name());
        assertEquals(expectedStatus, dockerTool.status());
        assertEquals(expectedAvailable, service.checkDockerAvailable());
        return dockerTool;
    }

    private static Field accessibleField(String name) throws Exception {
        Field f = DockerClientFactory.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test
    void testDetectToolsIncludesRichMetadataForAllCuratedTools() throws Exception {
        SystemToolService service = new SystemToolService(null, null, null,
                () -> new DockerVersionInfo("27.0.3", "1.46"), () -> true);
        List<SystemTool> tools = service.detectTools().get();

        assertEquals(5, tools.size());
        for (String expectedName : List.of("Ripgrep", "Git", "Universal Ctags", "Java", "Docker")) {
            assertTrue(tools.stream().anyMatch(t -> t.name().equals(expectedName)));
        }
        for (SystemTool tool : tools) {
            assertNotNull(tool.name());
            assertNotNull(tool.status());
            assertNotNull(tool.version());
            assertFalse(tool.description().isBlank());
            assertFalse(tool.license().isBlank());
            assertTrue(tool.websiteUrl().startsWith("https://"));
        }
        assertEquals(service.checkDockerAvailable(), service.isDockerAvailable().get());
    }

    @Test
    void testParseVersionExtractsCleanFirstLineOnly() {
        assertEquals("ripgrep 14.1.0", SystemToolService.parseVersion(
                "ripgrep 14.1.0\nfeatures:-simd-accel,+pcre2\nsimd(compile):+SSE2,-SSSE3,-AVX2\n", "Ripgrep"));
        assertEquals("Universal Ctags 5.9.0", SystemToolService.parseVersion(
                "Universal Ctags 5.9.0, Copyright (C) 2015-2022 Universal Ctags Team\n", "Universal Ctags"));
        String expectedJavaVer = "version " + '"' + "25.0.4" + '"' + " 2026-07-21 LTS";
        assertEquals(expectedJavaVer, SystemToolService.parseVersion(
                "openjdk " + expectedJavaVer + "\nOpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)\n",
                "Java"));
        assertEquals("git version 2.43.0", SystemToolService.parseVersion("git version 2.43.0\n", "Git"));
        assertEquals("ripgrep 14.1.0", SystemToolService
                .parseVersion("ripgrep 14.1.0features:-simd-accel,+pcre2simd(compile):+SSE2", "Ripgrep"));
        assertEquals("Universal Ctags 5.9.0", SystemToolService
                .parseVersion("Universal Ctags 5.9.0, Copyright (C) 2015 Universal Ctags Team", "Universal Ctags"));
        assertEquals(expectedJavaVer,
                SystemToolService.parseVersion(expectedJavaVer
                        + "OpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)OpenJDK 64-Bit Server VM",
                        "Java"));
        assertTrue(SystemToolService
                .parseVersion("version " + '"'
                        + "25.0.4-custom-build-identifier-with-extra-suffixes-exceeding-fifty-chars" + '"', "Java")
                .length() <= 50);
        assertEquals("Unknown", SystemToolService.parseVersion("", "Git"));
        assertEquals("Unknown", SystemToolService.parseVersion("   \n  ", "Git"));
    }

    @Test
    void testLegacyTomlCacheEnrichesMissingLicenseWebsiteAndLegacyDescriptions() throws Exception {
        TomlMapper mapper = AiConfig.SHARED_TOML_MAPPER;
        SettingsService settingsService = new SettingsService(mapper, null);
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

        String legacyToml = """
                [[tools]]
                name = "Ripgrep"
                version = "ripgrep 14.1.0features:-simd-accel,+pcre2sim..."
                status = "Detected"
                description = "Required for fast file searching."

                [[tools]]
                name = "Java"
                version = 'version "25.0.4" 2026-07-21 LTSOpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)OpenJDK 64-Bit Server VM'
                status = "Detected"
                description = "The runtime environment for this application."

                [[tools]]
                name = "Docker"
                version = "Docker 28.0.1 (API 1.48)"
                status = "Detected"
                description = "Required for isolated build and test execution via Testcontainers."
                """;
        Files.writeString(tempDir.resolve(SystemToolService.SYSTEM_TOOLS_FILE), legacyToml);

        SystemToolService service = new SystemToolService(settingsService, mapper, event -> {
        });
        List<SystemTool> loaded = service.detectTools().get(5, TimeUnit.SECONDS);

        assertEquals(3, loaded.size());
        SystemTool rg = loaded.get(0);
        assertEquals("Ripgrep", rg.name());
        assertEquals("ripgrep 14.1.0", rg.version());
        assertEquals("Unlicense / MIT", rg.license());
        assertEquals("https://github.com/BurntSushi/ripgrep", rg.websiteUrl());
        assertTrue(rg.description().contains("GrepService"));

        SystemTool javaTool = loaded.get(1);
        assertEquals("Java", javaTool.name());
        assertEquals("version " + '"' + "25.0.4" + '"' + " 2026-07-21 LTS", javaTool.version());
        assertEquals("GPL-2.0 with Classpath Exception", javaTool.license());
        assertEquals("https://adoptium.net", javaTool.websiteUrl());

        SystemTool docker = loaded.get(2);
        assertEquals("Docker", docker.name());
        assertEquals("Apache-2.0", docker.license());
        assertEquals("https://www.docker.com", docker.websiteUrl());
        assertTrue(docker.description().contains("BuildContainerService"));
    }

    @Test
    void testDetectDockerToolDetectedWithVersionAndApiVersion() {
        SystemTool dockerTool = assertDockerTool(() -> new DockerVersionInfo("28.0.1", "1.48"), true, "Detected", true);
        assertEquals("Docker 28.0.1 (API 1.48)", dockerTool.version());
        assertEquals("Apache-2.0", dockerTool.license());
        assertEquals("https://www.docker.com", dockerTool.websiteUrl());
    }

    @Test
    void testDetectDockerToolErrorOnApiVersionMismatchOrHandshakeFailure() {
        SystemTool dockerTool = assertDockerTool(() -> {
            throw new IllegalStateException("Could not connect to Docker",
                    new RuntimeException("client version 1.32 is too old. Minimum supported API version is 1.44"));
        }, false, "Error", false);
        assertTrue(dockerTool.version().contains("client version 1.32 is too old"));
    }

    @Test
    void testDetectDockerToolMissingWhenDockerNotInstalled() {
        SystemTool dockerTool = assertDockerTool(() -> {
            throw new IllegalStateException(
                    "Could not find a valid Docker environment. Please see logs and check configuration");
        }, false, "Missing", false);
        assertEquals("Not found", dockerTool.version());
    }

    @Test
    void testDetectDockerToolErrorWhenCliPresentButTestcontainersCannotConnect() {
        SystemTool dockerTool = assertDockerTool(() -> {
            throw new IllegalStateException(
                    "Could not find a valid Docker environment. Please see logs and check configuration");
        }, true, "Error", false);
        assertTrue(dockerTool.version().contains("Could not find a valid Docker environment"));
    }

    @Test
    void testTomlPersistenceAndInMemoryCacheHitsVsForcedRefresh() throws Exception {
        TomlMapper mapper = AiConfig.SHARED_TOML_MAPPER;
        SettingsService settingsService = new SettingsService(mapper, null);
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

        List<SystemToolsUpdatedEvent> publishedEvents = new ArrayList<>();
        AtomicInteger probeCount = new AtomicInteger(0);

        SystemToolService service1 = new SystemToolService(settingsService, mapper, event -> {
            if (event instanceof SystemToolsUpdatedEvent e)
                publishedEvents.add(e);
        });
        service1.setToolProber(() -> {
            int call = probeCount.incrementAndGet();
            return List.of(new SystemTool("Git", "2.45." + call, "Detected", "Git VCS"), new SystemTool("Docker",
                    call == 1 ? "Not found" : "Docker 28.0.0", call == 1 ? "Missing" : "Detected", "Testcontainers"));
        });

        assertFalse(service1.hasCachedTools());
        assertTrue(service1.getCachedTools().isEmpty());

        List<SystemTool> firstResult = service1.detectTools().get(5, TimeUnit.SECONDS);
        assertEquals(1, probeCount.get());
        assertEquals(2, firstResult.size());
        assertTrue(service1.hasCachedTools());
        assertFalse(service1.checkDockerAvailable());
        assertFalse(service1.isDockerAvailable().get(5, TimeUnit.SECONDS));
        assertEquals(1, probeCount.get());
        assertEquals(1, publishedEvents.size());
        assertFalse(publishedEvents.get(0).dockerAvailable());
        assertTrue(Files.exists(tempDir.resolve(SystemToolService.SYSTEM_TOOLS_FILE)));

        AtomicInteger secondProbeCount = new AtomicInteger(0);
        SystemToolService service2 = new SystemToolService(settingsService, mapper, event -> {
        });
        service2.setToolProber(() -> {
            secondProbeCount.incrementAndGet();
            return List.of();
        });

        final boolean[] edtHasCached = new boolean[1];
        final CompletableFuture<List<SystemTool>>[] edtDetectFuture = new CompletableFuture[1];
        SwingUtilities.invokeAndWait(() -> {
            edtHasCached[0] = service2.hasCachedTools();
            edtDetectFuture[0] = service2.detectTools();
        });
        assertFalse(edtHasCached[0]);
        List<SystemTool> diskLoaded = edtDetectFuture[0].get(5, TimeUnit.SECONDS);
        assertTrue(service2.hasCachedTools());
        assertEquals(2, diskLoaded.size());
        assertEquals("2.45.1", diskLoaded.get(0).version());
        assertEquals("GPL-2.0", diskLoaded.get(0).license());
        assertEquals("https://git-scm.com", diskLoaded.get(0).websiteUrl());
        assertEquals(0, secondProbeCount.get());
        assertEquals(diskLoaded, service2.detectTools().get(5, TimeUnit.SECONDS));
        assertEquals(0, secondProbeCount.get());

        List<SystemTool> refreshed = service1.refreshTools().get(5, TimeUnit.SECONDS);
        assertEquals(2, probeCount.get());
        assertEquals("2.45.2", refreshed.get(0).version());
        assertTrue(service1.checkDockerAvailable());
        assertTrue(service1.isDockerAvailable().get(5, TimeUnit.SECONDS));
        assertEquals(2, publishedEvents.size());
        assertTrue(publishedEvents.get(1).dockerAvailable());
    }

    @Test
    void testConcurrentRefreshDeduplication() throws Exception {
        SystemToolService service = new SystemToolService(null, null, null);
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger probeCount = new AtomicInteger(0);

        service.setToolProber(() -> {
            probeCount.incrementAndGet();
            probeStarted.countDown();
            try {
                releaseProbe.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(new SystemTool("Docker", "28.0", "Detected", "Desc"));
        });

        CompletableFuture<List<SystemTool>> f1 = service.refreshTools();
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<List<SystemTool>> f2 = service.refreshTools();
        CompletableFuture<Boolean> dockerFuture = service.refreshDockerAvailable();
        assertSame(f1, f2);

        releaseProbe.countDown();
        assertEquals(1, f1.get(5, TimeUnit.SECONDS).size());
        assertEquals(1, f2.get(5, TimeUnit.SECONDS).size());
        assertTrue(dockerFuture.get(5, TimeUnit.SECONDS));
        assertEquals(1, probeCount.get());
    }

    @Test
    void testResetTestcontainersCachesAndProbeWithTestcontainersCleanup() throws Exception {
        DockerClientFactory factory = DockerClientFactory.instance();
        Field failureField = accessibleField("cachedClientFailure");
        Field clientField = accessibleField("client");
        Field strategyField = accessibleField("strategy");

        Object origFailure = failureField.get(factory);
        Object origClient = clientField.get(factory);
        Object origStrategy = strategyField.get(factory);

        try {
            failureField.set(factory, new RuntimeException("latched failure"));
            assertNotNull(failureField.get(factory));
            SystemToolService.resetTestcontainersFailureCache();
            assertNull(failureField.get(factory));

            DockerClient mockClient = mock(DockerClient.class);
            DockerClientProviderStrategy mockStrategy = mock(DockerClientProviderStrategy.class);
            failureField.set(factory, new RuntimeException("latched failure"));
            clientField.set(factory, mockClient);
            strategyField.set(factory, mockStrategy);

            SystemToolService.resetTestcontainersClientAndFailureCache();
            assertNull(failureField.get(factory));
            assertNull(clientField.get(factory));
            assertNull(strategyField.get(factory));

            VersionCmd okVersionCmd = mock(VersionCmd.class);
            Version mockVersion = mock(Version.class);
            when(mockVersion.getVersion()).thenReturn("28.0.4");
            when(mockVersion.getApiVersion()).thenReturn("1.48");
            when(okVersionCmd.exec()).thenReturn(mockVersion);
            when(mockClient.versionCmd()).thenReturn(okVersionCmd);

            failureField.set(factory, new RuntimeException("stale failure before recovery"));
            clientField.set(factory, mockClient);
            strategyField.set(factory, mockStrategy);

            DockerVersionInfo versionInfo = SystemToolService.probeWithTestcontainers();
            assertEquals("28.0.4", versionInfo.version());
            assertEquals("1.48", versionInfo.apiVersion());
            assertNull(failureField.get(factory));
            assertSame(mockClient, clientField.get(factory));

            VersionCmd failingVersionCmd = mock(VersionCmd.class);
            when(failingVersionCmd.exec()).thenThrow(new RuntimeException("Daemon stopped"));
            when(mockClient.versionCmd()).thenReturn(failingVersionCmd);

            assertEquals("Daemon stopped",
                    assertThrows(RuntimeException.class, SystemToolService::probeWithTestcontainers).getMessage());
            assertNull(failureField.get(factory));
            assertNull(clientField.get(factory));
            assertNull(strategyField.get(factory));
        } finally {
            failureField.set(factory, origFailure);
            clientField.set(factory, origClient);
            strategyField.set(factory, origStrategy);
        }
    }
}
