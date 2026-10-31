package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;

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

    @Test
    void testAreAllRequiredToolsDetected() {
        assertFalse(SystemToolService.areAllRequiredToolsDetected(null));
        assertFalse(SystemToolService.areAllRequiredToolsDetected(List.of()));

        List<SystemTool> allRequiredDetectedDockerMissing = List.of(
                SystemTool.ofKnownTool(SystemTool.TOOL_RIPGREP, "14.1.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_GIT, "2.43.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_CTAGS, "5.9.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_DOCKER, "Not found", SystemToolService.STATUS_MISSING));
        assertTrue(SystemToolService.areAllRequiredToolsDetected(allRequiredDetectedDockerMissing));

        List<SystemTool> ctagsMissing = List.of(
                SystemTool.ofKnownTool(SystemTool.TOOL_RIPGREP, "14.1.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_GIT, "2.43.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_CTAGS, "Not found", SystemToolService.STATUS_MISSING),
                SystemTool.ofKnownTool(SystemTool.TOOL_DOCKER, "28.0.0", SystemToolService.STATUS_DETECTED));
        assertFalse(SystemToolService.areAllRequiredToolsDetected(ctagsMissing));

        List<SystemTool> incompleteList = List.of(
                SystemTool.ofKnownTool(SystemTool.TOOL_RIPGREP, "14.1.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_GIT, "2.43.0", SystemToolService.STATUS_DETECTED));
        assertFalse(SystemToolService.areAllRequiredToolsDetected(incompleteList));

        List<SystemTool> customRequiredMissing = List.of(
                SystemTool.ofKnownTool(SystemTool.TOOL_RIPGREP, "14.1.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_GIT, "2.43.0", SystemToolService.STATUS_DETECTED),
                SystemTool.ofKnownTool(SystemTool.TOOL_CTAGS, "5.9.0", SystemToolService.STATUS_DETECTED),
                new SystemTool("CustomRequired", "Not found", SystemToolService.STATUS_MISSING, "Desc", "MIT",
                        "https://example.com", true));
        assertFalse(SystemToolService.areAllRequiredToolsDetected(customRequiredMissing));
    }

    @Test
    void testParseVersionExtractsCleanFirstLineOnly() {
        assertEquals("ripgrep 14.1.0", SystemToolService.parseVersion("""
                ripgrep 14.1.0
                features:-simd-accel,+pcre2
                simd(compile):+SSE2,-SSSE3,-AVX2
                """));
        assertEquals("Universal Ctags 5.9.0", SystemToolService.parseVersion("""
                Universal Ctags 5.9.0, Copyright (C) 2015-2022 Universal Ctags Team
                """));
        assertEquals("git version 2.43.0", SystemToolService.parseVersion("""
                git version 2.43.0
                """));
        assertEquals("ripgrep 14.1.0",
                SystemToolService.parseVersion("ripgrep 14.1.0features:-simd-accel,+pcre2simd(compile):+SSE2"));
        assertEquals("Universal Ctags 5.9.0",
                SystemToolService.parseVersion("Universal Ctags 5.9.0, Copyright (C) 2015 Universal Ctags Team"));
        assertTrue(SystemToolService
                .parseVersion("ripgrep 14.1.0-custom-build-identifier-with-extra-suffixes-exceeding-fifty-chars")
                .length() <= 50);
        assertEquals("Unknown", SystemToolService.parseVersion(""));
        assertEquals("Unknown", SystemToolService.parseVersion("""

                """));
    }

    @Test
    void testDetectDockerToolStatusTransitions() {
        SystemTool okTool = assertDockerTool(() -> new DockerVersionInfo("28.0.1", "1.48"), true, "Detected", true);
        assertEquals("Docker 28.0.1 (API 1.48)", okTool.version());

        SystemTool errTool = assertDockerTool(() -> {
            throw new IllegalStateException("Could not connect to Docker",
                    new RuntimeException("client version 1.32 is too old. Minimum supported API version is 1.44"));
        }, false, "Error", false);
        assertTrue(errTool.version().contains("client version 1.32 is too old"));

        SystemTool missingTool = assertDockerTool(() -> {
            throw new IllegalStateException(
                    "Could not find a valid Docker environment. Please see logs and check configuration");
        }, false, "Missing", false);
        assertEquals("Not found", missingTool.version());

        SystemTool cliErrTool = assertDockerTool(() -> {
            throw new IllegalStateException(
                    "Could not find a valid Docker environment. Please see logs and check configuration");
        }, true, "Error", false);
        assertTrue(cliErrTool.version().contains("Could not find a valid Docker environment"));
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
            return List.of(SystemTool.ofKnownTool("Git", "2.45." + call, "Detected"), SystemTool.ofKnownTool("Docker",
                    call == 1 ? "Not found" : "Docker 28.0.0", call == 1 ? "Missing" : "Detected"));
        });

        assertTrue(service1.getCachedTools().isEmpty());

        List<SystemTool> firstResult = service1.detectTools().get(5, TimeUnit.SECONDS);
        assertEquals(1, probeCount.get());
        assertEquals(2, firstResult.size());
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

        final CompletableFuture<List<SystemTool>>[] edtDetectFuture = new CompletableFuture[1];
        SwingUtilities.invokeAndWait(() -> {
            edtDetectFuture[0] = service2.detectTools();
        });
        List<SystemTool> diskLoaded = edtDetectFuture[0].get(5, TimeUnit.SECONDS);
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
            return List.of(SystemTool.ofKnownTool("Docker", "28.0", "Detected"));
        });

        CompletableFuture<List<SystemTool>> f1 = service.refreshTools();
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<List<SystemTool>> f2 = service.refreshTools();
        CompletableFuture<Boolean> dockerFuture = service.refreshDockerAvailable();

        releaseProbe.countDown();
        assertEquals(1, f1.get(5, TimeUnit.SECONDS).size());
        assertEquals(1, f2.get(5, TimeUnit.SECONDS).size());
        assertTrue(dockerFuture.get(5, TimeUnit.SECONDS));
        assertEquals(1, probeCount.get());
    }

}
