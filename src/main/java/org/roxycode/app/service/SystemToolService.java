package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.testcontainers.DockerClientFactory;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class SystemToolService {
    private static final Logger log = LoggerFactory.getLogger(SystemToolService.class);
    static final String SYSTEM_TOOLS_FILE = "system-tools.toml";
    private static final String RIPGREP = SystemTool.TOOL_RIPGREP;
    private static final String GIT = SystemTool.TOOL_GIT;
    private static final String CTAGS = SystemTool.TOOL_CTAGS;
    private static final String JAVA = SystemTool.TOOL_JAVA;
    private static final String DOCKER = SystemTool.TOOL_DOCKER;
    private static final String ARG_VERSION = "--version";
    public static final String STATUS_DETECTED = "Detected";
    public static final String STATUS_ERROR = "Error";
    public static final String STATUS_MISSING = "Missing";
    static final String VERSION_NOT_FOUND = "Not found";
    static final String VERSION_UNKNOWN = "Unknown";
    private static final int MAX_DOCKER_ERROR_LENGTH = 120;
    private static final int MAX_VERSION_LENGTH = 50;
    private static final List<String> LEGACY_VERSION_NOISE_MARKERS =
            List.of("features:", ", Copyright", "OpenJDK", "Java(TM)");

    public record DockerVersionInfo(String version, String apiVersion) {}

    public record SystemToolsCache(List<SystemTool> tools) {}

    @FunctionalInterface
    interface DockerProbe {
        DockerVersionInfo probe() throws Exception;
    }

    private final SettingsService settingsService;
    private final TomlMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DockerProbe dockerProbe;
    private final BooleanSupplier dockerCliPresentSupplier;
    private Supplier<List<SystemTool>> toolProber;
    private List<SystemTool> cachedTools;
    private CompletableFuture<List<SystemTool>> inFlightRefresh;

    @Autowired
    public SystemToolService(
            SettingsService settingsService,
            TomlMapper mapper,
            ApplicationEventPublisher eventPublisher) {
        this(
                settingsService,
                mapper,
                eventPublisher,
                SystemToolService::probeWithTestcontainers,
                SystemToolService::isDockerCliInstalled);
    }

    SystemToolService(DockerProbe dockerProbe, BooleanSupplier dockerCliPresentSupplier) {
        this(null, AiConfig.SHARED_TOML_MAPPER, event -> {}, dockerProbe, dockerCliPresentSupplier);
    }

    SystemToolService(
            SettingsService settingsService,
            TomlMapper mapper,
            ApplicationEventPublisher eventPublisher,
            DockerProbe dockerProbe,
            BooleanSupplier dockerCliPresentSupplier) {
        this.settingsService = settingsService;
        this.mapper = mapper != null ? mapper : AiConfig.SHARED_TOML_MAPPER;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {};
        this.dockerProbe = dockerProbe;
        this.dockerCliPresentSupplier = dockerCliPresentSupplier;
        this.toolProber = this::probeAllTools;
    }

    void setToolProber(Supplier<List<SystemTool>> toolProber) {
        this.toolProber = toolProber != null ? toolProber : this::probeAllTools;
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingThrowable"})
    static DockerVersionInfo probeWithTestcontainers() {
        resetTestcontainersFailureCache();
        try {
            DockerClient client = DockerClientFactory.instance().client();
            Version version = client.versionCmd().exec();
            return new DockerVersionInfo(
                    version != null ? version.getVersion() : null,
                    version != null ? version.getApiVersion() : null);
        } catch (Throwable t) {
            resetTestcontainersClientAndFailureCache();
            throw t;
        }
    }

    static void resetTestcontainersFailureCache() {
        clearDockerClientFactoryField(DockerClientFactory.instance(), "cachedClientFailure");
    }

    static void resetTestcontainersClientAndFailureCache() {
        DockerClientFactory factory = DockerClientFactory.instance();
        clearDockerClientFactoryField(factory, "cachedClientFailure");
        clearDockerClientFactoryField(factory, "client");
        clearDockerClientFactoryField(factory, "strategy");
    }

    private static void clearDockerClientFactoryField(
            DockerClientFactory factory, String fieldName) {
        try {
            FieldUtils.writeDeclaredField(factory, fieldName, null, true);
        } catch (ReflectiveOperationException | IllegalArgumentException | SecurityException e) {
            log.debug("Could not reset DockerClientFactory.{}: {}", fieldName, e.getMessage());
        }
    }

    static boolean isDockerCliInstalled() {
        try {
            ProcessResult versionResult =
                    ProcessRunner.run(List.of("docker", ARG_VERSION), null, true);
            return !versionResult.timedOut() && versionResult.exitCode() == 0;
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean hasCachedTools() {
        if (cachedTools != null && !cachedTools.isEmpty()) return true;
        if (SwingUtilities.isEventDispatchThread()) return false;
        return !loadDiskCacheIntoMemory().isEmpty();
    }

    private synchronized List<SystemTool> loadDiskCacheIntoMemory() {
        if (cachedTools != null && !cachedTools.isEmpty()) {
            return List.copyOf(cachedTools);
        }
        List<SystemTool> loaded = loadFromDisk();
        if (loaded != null && !loaded.isEmpty()) {
            cachedTools = List.copyOf(loaded);
            return cachedTools;
        }
        return List.of();
    }

    public synchronized List<SystemTool> getCachedTools() {
        return hasCachedTools() ? List.copyOf(cachedTools) : List.of();
    }

    public CompletableFuture<List<SystemTool>> detectTools() {
        synchronized (this) {
            if (cachedTools != null && !cachedTools.isEmpty()) {
                return CompletableFuture.completedFuture(List.copyOf(cachedTools));
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return CompletableFuture.supplyAsync(this::loadDiskCacheIntoMemory)
                    .thenCompose(
                            diskTools ->
                                    !diskTools.isEmpty()
                                            ? CompletableFuture.completedFuture(diskTools)
                                            : refreshTools());
        }
        List<SystemTool> diskTools = loadDiskCacheIntoMemory();
        return !diskTools.isEmpty() ? CompletableFuture.completedFuture(diskTools) : refreshTools();
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public synchronized CompletableFuture<List<SystemTool>> refreshTools() {
        if (inFlightRefresh != null && !inFlightRefresh.isDone()) {
            return inFlightRefresh;
        }
        CompletableFuture<List<SystemTool>> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            List<SystemTool> detected = List.copyOf(toolProber.get());
                            updateCacheAndPublish(detected);
                            return detected;
                        });
        inFlightRefresh = future;
        future.whenComplete(
                (res, ex) -> {
                    synchronized (SystemToolService.this) {
                        if (inFlightRefresh == future) {
                            inFlightRefresh = null;
                        }
                    }
                });
        return future;
    }

    public CompletableFuture<Boolean> isDockerAvailable() {
        synchronized (this) {
            if (cachedTools != null && !cachedTools.isEmpty()) {
                return CompletableFuture.completedFuture(isDockerDetected(cachedTools));
            }
        }
        return detectTools().thenApply(SystemToolService::isDockerDetected);
    }

    public CompletableFuture<Boolean> refreshDockerAvailable() {
        return refreshTools().thenApply(SystemToolService::isDockerDetected);
    }

    public boolean checkDockerAvailable() {
        synchronized (this) {
            if (hasCachedTools()) {
                return isDockerDetected(cachedTools);
            }
        }
        return STATUS_DETECTED.equals(detectDockerTool().status());
    }

    static boolean isDockerDetected(List<SystemTool> tools) {
        return tools != null
                && tools.stream()
                        .anyMatch(
                                t -> DOCKER.equals(t.name()) && STATUS_DETECTED.equals(t.status()));
    }

    private List<SystemTool> probeAllTools() {
        return List.of(
                detectTool(RIPGREP, "rg", ARG_VERSION),
                detectTool(GIT, "git", ARG_VERSION),
                detectTool(CTAGS, "ctags", ARG_VERSION),
                detectTool(JAVA, "java", "-version"),
                detectDockerTool());
    }

    private void updateCacheAndPublish(List<SystemTool> detected) {
        synchronized (this) {
            cachedTools = detected;
            saveToDisk(detected);
        }
        eventPublisher.publishEvent(
                new SystemToolsUpdatedEvent(detected, isDockerDetected(detected)));
    }

    Path getCacheFilePath() {
        Path homeDir = settingsService != null ? settingsService.getRoxyHomeDir() : null;
        return homeDir != null ? homeDir.resolve(SYSTEM_TOOLS_FILE) : null;
    }

    private List<SystemTool> loadFromDisk() {
        Path path = getCacheFilePath();
        if (path == null || !Files.exists(path)) return List.of();
        try {
            SystemToolsCache cache = mapper.readValue(path.toFile(), SystemToolsCache.class);
            if (cache == null || cache.tools() == null) return List.of();
            return cache.tools().stream()
                    .map(SystemTool::enrichLegacyMetadata)
                    .map(SystemToolService::sanitizeCachedToolVersion)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to load cached system tools from {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private void saveToDisk(List<SystemTool> tools) {
        Path path = getCacheFilePath();
        if (path == null) return;
        try {
            PathUtils.createParentDirectories(path);
            mapper.writeValue(path.toFile(), new SystemToolsCache(tools));
        } catch (IOException e) {
            log.warn("Failed to save system tools cache to {}: {}", path, e.getMessage());
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    SystemTool detectDockerTool() {
        try {
            DockerVersionInfo info = dockerProbe.probe();
            String serverVersion =
                    StringUtils.defaultIfBlank(
                            info != null ? StringUtils.trim(info.version()) : null,
                            VERSION_UNKNOWN);
            String apiVersion =
                    StringUtils.defaultIfBlank(
                            info != null ? StringUtils.trim(info.apiVersion()) : null, "");
            String versionDisplay =
                    !apiVersion.isEmpty()
                            ? "Docker " + serverVersion + " (API " + apiVersion + ")"
                            : "Docker " + serverVersion;
            return SystemTool.ofKnownTool(DOCKER, versionDisplay, STATUS_DETECTED);
        } catch (Throwable t) {
            ExceptionUtils.rethrowIfCancelled(t, "Docker detection cancelled");
            String rootMessage = extractDiagnosticMessage(t);
            log.warn("Testcontainers Docker detection failed: {}", rootMessage);
            if (isGenericEnvironmentNotFound(rootMessage)
                    && (dockerCliPresentSupplier == null
                            || !dockerCliPresentSupplier.getAsBoolean())) {
                return SystemTool.ofKnownTool(DOCKER, VERSION_NOT_FOUND, STATUS_MISSING);
            }
            return SystemTool.ofKnownTool(
                    DOCKER, formatDockerErrorMessage(rootMessage), STATUS_ERROR);
        }
    }

    private static String extractDiagnosticMessage(Throwable t) {
        Throwable root = ExceptionUtils.getRootCause(t);
        if (root != null && StringUtils.isNotBlank(root.getMessage())) {
            return root.getMessage().trim();
        }
        if (StringUtils.isNotBlank(t.getMessage())) {
            return t.getMessage().trim();
        }
        return root != null ? root.getClass().getSimpleName() : t.getClass().getSimpleName();
    }

    private static boolean isGenericEnvironmentNotFound(String message) {
        if (StringUtils.isBlank(message)) return true;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("could not find a valid docker environment")
                || lower.contains("previous attempts to find a docker environment failed")
                || "not found".equals(lower);
    }

    private static String formatDockerErrorMessage(String rawMessage) {
        return StringUtils.isBlank(rawMessage)
                ? "Testcontainers connection failed"
                : StringUtils.abbreviate(
                        StringUtils.normalizeSpace(rawMessage), MAX_DOCKER_ERROR_LENGTH);
    }

    private SystemTool detectTool(String name, String command, String versionArg) {
        try {
            ProcessResult result = ProcessRunner.run(List.of(command, versionArg), null, true);
            if (!result.timedOut() && result.exitCode() == 0) {
                return SystemTool.ofKnownTool(
                        name, parseVersion(result.output(), name), STATUS_DETECTED);
            }
            log.warn("Tool {} detection failed with exit code {}", name, result.exitCode());
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Tool {} not detected: {}", name, e.getMessage());
        }
        return SystemTool.ofKnownTool(name, VERSION_NOT_FOUND, STATUS_MISSING);
    }

    private static SystemTool sanitizeCachedToolVersion(SystemTool tool) {
        if (tool == null
                || StringUtils.isBlank(tool.version())
                || VERSION_NOT_FOUND.equals(tool.version())) {
            return tool;
        }
        return tool.withVersion(
                (DOCKER.equals(tool.name()) && STATUS_ERROR.equals(tool.status()))
                        ? formatDockerErrorMessage(tool.version())
                        : parseVersion(tool.version(), tool.name()));
    }

    static String parseVersion(String output, String toolName) {
        if (StringUtils.isBlank(output)) return VERSION_UNKNOWN;
        String firstLine =
                output.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .findFirst()
                        .orElse(VERSION_UNKNOWN);
        if (VERSION_UNKNOWN.equals(firstLine)) return firstLine;
        if (JAVA.equals(toolName) && firstLine.contains("version")) {
            firstLine = firstLine.substring(firstLine.indexOf("version"));
        }
        for (String marker : LEGACY_VERSION_NOISE_MARKERS) {
            int idx = firstLine.indexOf(marker);
            if (idx > 0) {
                firstLine = firstLine.substring(0, idx).trim();
            }
        }
        return StringUtils.abbreviate(firstLine, MAX_VERSION_LENGTH);
    }
}
