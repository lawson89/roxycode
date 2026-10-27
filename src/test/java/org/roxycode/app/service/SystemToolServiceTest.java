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

    @TempDir Path tempDir;

    private static SystemTool assertDockerTool(
            DockerProbe probe,
            boolean cliPresent,
            String expectedStatus,
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
    void testDetectToolsIncludesDockerAndJava() throws Exception {
        SystemToolService service =
                new SystemToolService(
                        null,
                        null,
                        null,
                        () -> new DockerVersionInfo("27.0.3", "1.46"),
                        () -> true);
        List<SystemTool> tools = service.detectTools().get();

        assertFalse(tools.isEmpty());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("Java")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("Docker")));
        for (SystemTool tool : tools) {
            assertNotNull(tool.name());
            assertNotNull(tool.status());
            assertNotNull(tool.version());
            assertNotNull(tool.description());
        }

        Boolean dockerAvailable = service.isDockerAvailable().get();
        assertEquals(service.checkDockerAvailable(), dockerAvailable);
    }

    @Test
    void testDetectDockerToolDetectedWithVersionAndApiVersion() {
        SystemTool dockerTool =
                assertDockerTool(
                        () -> new DockerVersionInfo("28.0.1", "1.48"), true, "Detected", true);
        assertEquals("Docker 28.0.1 (API 1.48)", dockerTool.version());
    }

    @Test
    void testDetectDockerToolErrorOnApiVersionMismatchOrHandshakeFailure() {
        SystemTool dockerTool =
                assertDockerTool(
                        () -> {
                            throw new IllegalStateException(
                                    "Could not connect to Docker",
                                    new RuntimeException(
                                            "client version 1.32 is too old. Minimum supported API version is 1.44"));
                        },
                        false,
                        "Error",
                        false);
        assertTrue(dockerTool.version().contains("client version 1.32 is too old"));
    }

    @Test
    void testDetectDockerToolMissingWhenDockerNotInstalled() {
        SystemTool dockerTool =
                assertDockerTool(
                        () -> {
                            throw new IllegalStateException(
                                    "Could not find a valid Docker environment. Please see logs and check configuration");
                        },
                        false,
                        "Missing",
                        false);
        assertEquals("Not found", dockerTool.version());
    }

    @Test
    void testDetectDockerToolErrorWhenCliPresentButTestcontainersCannotConnect() {
        SystemTool dockerTool =
                assertDockerTool(
                        () -> {
                            throw new IllegalStateException(
                                    "Could not find a valid Docker environment. Please see logs and check configuration");
                        },
                        true,
                        "Error",
                        false);
        assertTrue(dockerTool.version().contains("Could not find a valid Docker environment"));
    }

    @Test
    void testTomlPersistenceAndInMemoryCacheHitsVsForcedRefresh() throws Exception {
        TomlMapper mapper = AiConfig.SHARED_TOML_MAPPER;
        SettingsService settingsService = new SettingsService(mapper, null);
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

        List<SystemToolsUpdatedEvent> publishedEvents = new ArrayList<>();
        AtomicInteger probeCount = new AtomicInteger(0);

        SystemToolService service1 =
                new SystemToolService(
                        settingsService,
                        mapper,
                        event -> {
                            if (event instanceof SystemToolsUpdatedEvent e) {
                                publishedEvents.add(e);
                            }
                        });
        service1.setToolProber(
                () -> {
                    int call = probeCount.incrementAndGet();
                    return List.of(
                            new SystemTool("Git", "2.45." + call, "Detected", "Git VCS"),
                            new SystemTool(
                                    "Docker",
                                    call == 1 ? "Not found" : "Docker 28.0.0",
                                    call == 1 ? "Missing" : "Detected",
                                    "Testcontainers"));
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
        SystemToolService service2 = new SystemToolService(settingsService, mapper, event -> {});
        service2.setToolProber(
                () -> {
                    secondProbeCount.incrementAndGet();
                    return List.of();
                });

        // On EDT, hasCachedTools() does not block on disk I/O, while detectTools() loads disk cache
        // off EDT
        final boolean[] edtHasCached = new boolean[1];
        final CompletableFuture<List<SystemTool>>[] edtDetectFuture = new CompletableFuture[1];
        javax.swing.SwingUtilities.invokeAndWait(
                () -> {
                    edtHasCached[0] = service2.hasCachedTools();
                    edtDetectFuture[0] = service2.detectTools();
                });
        assertFalse(edtHasCached[0], "hasCachedTools() on EDT must not read disk synchronously");
        List<SystemTool> diskLoaded = edtDetectFuture[0].get(5, TimeUnit.SECONDS);
        assertTrue(service2.hasCachedTools());
        assertEquals(2, diskLoaded.size());
        assertEquals("2.45.1", diskLoaded.get(0).version());
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

        service.setToolProber(
                () -> {
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

            assertEquals(
                    "Daemon stopped",
                    assertThrows(RuntimeException.class, SystemToolService::probeWithTestcontainers)
                            .getMessage());
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
