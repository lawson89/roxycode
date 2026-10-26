package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
    private static final String JAVA = "Java";
    private static final String DOCKER = "Docker";
    private static final String ARG_VERSION = "--version";
    public static final String STATUS_DETECTED = "Detected";
    public static final String STATUS_ERROR = "Error";
    public static final String STATUS_MISSING = "Missing";
    static final String VERSION_NOT_FOUND = "Not found";
    private static final int MAX_DOCKER_ERROR_LENGTH = 120;
    private static final String DOCKER_DESCRIPTION =
            "Required for isolated build and test execution via Testcontainers.";

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

    /**
     * Checks whether system tool results are already cached in memory or persisted on disk without
     * running any subprocess or Docker probes.
     */
    public synchronized boolean hasCachedTools() {
        if (cachedTools != null && !cachedTools.isEmpty()) {
            return true;
        }
        List<SystemTool> loaded = loadFromDisk();
        if (loaded != null && !loaded.isEmpty()) {
            cachedTools = List.copyOf(loaded);
            return true;
        }
        return false;
    }

    /**
     * Returns the cached system tool results (loading from disk if available) without triggering a
     * new probe, or an empty list if no cached state exists.
     */
    public synchronized List<SystemTool> getCachedTools() {
        if (hasCachedTools()) {
            return List.copyOf(cachedTools);
        }
        return List.of();
    }

    /**
     * Returns the cached system tools immediately if available, or performs an asynchronous
     * detection and caches/persists the result.
     */
    public CompletableFuture<List<SystemTool>> detectTools() {
        synchronized (this) {
            if (hasCachedTools()) {
                return CompletableFuture.completedFuture(List.copyOf(cachedTools));
            }
        }
        return refreshTools();
    }

    /**
     * Forces a fresh asynchronous probe of all system tools, updating the in-memory and disk cache
     * and deduplicating concurrent in-flight refresh requests.
     */
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

    /**
     * Asynchronously checks whether Docker is reachable and compatible with Testcontainers, using
     * cached state if available.
     */
    public CompletableFuture<Boolean> isDockerAvailable() {
        synchronized (this) {
            if (hasCachedTools()) {
                return CompletableFuture.completedFuture(isDockerDetected(cachedTools));
            }
        }
        return detectTools().thenApply(SystemToolService::isDockerDetected);
    }

    /**
     * Forces a fresh asynchronous tool refresh and returns whether Docker is reachable and
     * compatible with Testcontainers.
     */
    public CompletableFuture<Boolean> refreshDockerAvailable() {
        return refreshTools().thenApply(SystemToolService::isDockerDetected);
    }

    /**
     * Synchronously checks whether Docker is reachable and compatible with Testcontainers, using
     * cached state if available.
     */
    public boolean checkDockerAvailable() {
        synchronized (this) {
            if (hasCachedTools()) {
                return isDockerDetected(cachedTools);
            }
        }
        return STATUS_DETECTED.equals(detectDockerTool().status());
    }

    static boolean isDockerDetected(List<SystemTool> tools) {
        if (tools == null) {
            return false;
        }
        return tools.stream()
                .anyMatch(t -> DOCKER.equals(t.name()) && STATUS_DETECTED.equals(t.status()));
    }

    private List<SystemTool> probeAllTools() {
        List<SystemTool> tools = new ArrayList<>();
        tools.add(detectTool("Ripgrep", "rg", ARG_VERSION, "Required for fast file searching."));
        tools.add(
                detectTool("Git", "git", ARG_VERSION, "Required for version control integration."));
        tools.add(
                detectTool(
                        "Universal Ctags",
                        "ctags",
                        ARG_VERSION,
                        "Required for code navigation and symbol extraction."));
        tools.add(
                detectTool(
                        JAVA, "java", "-version", "The runtime environment for this application."));
        tools.add(detectDockerTool());
        return tools;
    }

    private void updateCacheAndPublish(List<SystemTool> detected) {
        synchronized (this) {
            cachedTools = detected;
            saveToDisk(detected);
        }
        boolean dockerAvail = isDockerDetected(detected);
        eventPublisher.publishEvent(new SystemToolsUpdatedEvent(detected, dockerAvail));
    }

    Path getCacheFilePath() {
        if (settingsService == null) {
            return null;
        }
        Path homeDir = settingsService.getRoxyHomeDir();
        return homeDir != null ? homeDir.resolve(SYSTEM_TOOLS_FILE) : null;
    }

    private List<SystemTool> loadFromDisk() {
        Path path = getCacheFilePath();
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        try {
            SystemToolsCache cache = mapper.readValue(path.toFile(), SystemToolsCache.class);
            return cache != null && cache.tools() != null ? cache.tools() : List.of();
        } catch (IOException e) {
            log.warn("Failed to load cached system tools from {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private void saveToDisk(List<SystemTool> tools) {
        Path path = getCacheFilePath();
        if (path == null) {
            return;
        }
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
                            info != null ? StringUtils.trim(info.version()) : null, "Unknown");
            String apiVersion =
                    StringUtils.defaultIfBlank(
                            info != null ? StringUtils.trim(info.apiVersion()) : null, "");
            String versionDisplay =
                    !apiVersion.isEmpty()
                            ? "Docker " + serverVersion + " (API " + apiVersion + ")"
                            : "Docker " + serverVersion;
            return new SystemTool(DOCKER, versionDisplay, STATUS_DETECTED, DOCKER_DESCRIPTION);
        } catch (Throwable t) {
            ExceptionUtils.rethrowIfCancelled(t, "Docker detection cancelled");
            String rootMessage = extractDiagnosticMessage(t);
            log.warn("Testcontainers Docker detection failed: {}", rootMessage);
            if (isGenericEnvironmentNotFound(rootMessage)
                    && (dockerCliPresentSupplier == null
                            || !dockerCliPresentSupplier.getAsBoolean())) {
                return new SystemTool(
                        DOCKER, VERSION_NOT_FOUND, STATUS_MISSING, DOCKER_DESCRIPTION);
            }
            return new SystemTool(
                    DOCKER,
                    formatDockerErrorMessage(rootMessage),
                    STATUS_ERROR,
                    DOCKER_DESCRIPTION);
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
        if (StringUtils.isBlank(message)) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("could not find a valid docker environment")
                || lower.contains("previous attempts to find a docker environment failed")
                || "not found".equals(lower);
    }

    private static String formatDockerErrorMessage(String rawMessage) {
        if (StringUtils.isBlank(rawMessage)) {
            return "Testcontainers connection failed";
        }
        String normalized = StringUtils.normalizeSpace(rawMessage);
        return StringUtils.abbreviate(normalized, MAX_DOCKER_ERROR_LENGTH);
    }

    private SystemTool detectTool(
            String name, String command, String versionArg, String description) {
        try {
            ProcessResult result = ProcessRunner.run(List.of(command, versionArg), null, true);
            if (!result.timedOut() && result.exitCode() == 0) {
                String version = parseVersion(result.output(), name);
                return new SystemTool(name, version, STATUS_DETECTED, description);
            } else {
                log.warn("Tool {} detection failed with exit code {}", name, result.exitCode());
                return new SystemTool(name, VERSION_NOT_FOUND, STATUS_MISSING, description);
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Tool {} not detected: {}", name, e.getMessage());
            return new SystemTool(name, VERSION_NOT_FOUND, STATUS_MISSING, description);
        }
    }

    private String parseVersion(String output, String toolName) {
        if (StringUtils.isEmpty(output)) return "Unknown";
        String fullOutput = output.trim();
        if (JAVA.equals(toolName)) {
            return fullOutput.contains("version")
                    ? fullOutput.substring(fullOutput.indexOf("version"))
                    : fullOutput;
        }
        return StringUtils.abbreviate(fullOutput, 50);
    }
}
