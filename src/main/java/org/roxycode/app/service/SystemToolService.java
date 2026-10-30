package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
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
    private static final String DOCKER = SystemTool.TOOL_DOCKER;
    private static final String ARG_VERSION = "--version";
    public static final String STATUS_DETECTED = "Detected";
    public static final String STATUS_ERROR = "Error";
    public static final String STATUS_MISSING = "Missing";
    static final String VERSION_NOT_FOUND = "Not found";
    static final String VERSION_UNKNOWN = "Unknown";
    private static final int MAX_DOCKER_ERROR_LENGTH = 120;
    private static final int MAX_VERSION_LENGTH = 50;
    private static final List<String> VERSION_NOISE_MARKERS = List.of("features:", ", Copyright");

    public record DockerVersionInfo(String version, String apiVersion) {
    }

    public static class SystemToolsCache {
        private List<SystemTool> tools;
        public SystemToolsCache() {
        }

        public SystemToolsCache(List<SystemTool> tools) {
            this.tools = tools;
        }

        public List<SystemTool> getTools() {
            return tools;
        }

        public void setTools(List<SystemTool> tools) {
            this.tools = tools;
        }
    }

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
    private final AsyncLoadingCache<String, List<SystemTool>> toolsCache;

    @Autowired
    public SystemToolService(SettingsService settingsService, TomlMapper mapper,
            ApplicationEventPublisher eventPublisher) {
        this(settingsService, mapper, eventPublisher, SystemToolService::probeWithTestcontainers,
                SystemToolService::isDockerCliInstalled);
    }

    SystemToolService(DockerProbe dockerProbe, BooleanSupplier dockerCliPresentSupplier) {
        this(null, AiConfig.SHARED_TOML_MAPPER, event -> {
        }, dockerProbe, dockerCliPresentSupplier);
    }

    SystemToolService(SettingsService settingsService, TomlMapper mapper, ApplicationEventPublisher eventPublisher,
            DockerProbe dockerProbe, BooleanSupplier dockerCliPresentSupplier) {
        this.settingsService = settingsService;
        this.mapper = mapper != null ? mapper : AiConfig.SHARED_TOML_MAPPER;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {
        };
        this.dockerProbe = dockerProbe;
        this.dockerCliPresentSupplier = dockerCliPresentSupplier;
        this.toolProber = this::probeAllTools;
        this.toolsCache = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES)
                .buildAsync((key, executor) -> CompletableFuture.supplyAsync(this::probeAndPublish, executor));
    }

    private List<SystemTool> loadOrProbe() {
        List<SystemTool> loaded = loadFromDisk();
        if (!loaded.isEmpty()) {
            return loaded;
        }
        return probeAndPublish();
    }

    private List<SystemTool> probeAndPublish() {
        List<SystemTool> detected = List.copyOf(toolProber.get());
        saveToDisk(detected);
        eventPublisher.publishEvent(new SystemToolsUpdatedEvent(detected, isDockerDetected(detected)));
        return detected;
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
            return new DockerVersionInfo(version != null ? version.getVersion() : null,
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

    static void clearDockerClientFactoryField(DockerClientFactory factory, String fieldName) {
        try {
            if (FieldUtils.getDeclaredField(DockerClientFactory.class, fieldName, true) == null) {
                log.warn("DockerClientFactory field '{}' not found; Testcontainers internals may have changed",
                        fieldName);
                return;
            }
            FieldUtils.writeDeclaredField(factory, fieldName, null, true);
        } catch (ReflectiveOperationException | IllegalArgumentException | SecurityException e) {
            log.debug("Could not reset DockerClientFactory.{}: {}", fieldName, e.getMessage());
        }
    }

    static boolean isDockerCliInstalled() {
        return ProcessRunner.run(List.of("docker", ARG_VERSION)).isSuccess();
    }

    public boolean hasCachedTools() {
        CompletableFuture<List<SystemTool>> future = toolsCache.getIfPresent("all");
        return future != null && future.isDone() && !future.getNow(List.of()).isEmpty();
    }

    public List<SystemTool> getCachedTools() {
        CompletableFuture<List<SystemTool>> future = toolsCache.getIfPresent("all");
        if (future != null && future.isDone()) {
            return future.getNow(List.of());
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return List.of();
        }
        List<SystemTool> loaded = loadFromDisk();
        if (!loaded.isEmpty()) {
            toolsCache.synchronous().put("all", loaded);
            return loaded;
        }
        return List.of();
    }

    public CompletableFuture<List<SystemTool>> detectTools() {
        List<SystemTool> cached = getCachedTools();
        if (!cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        return toolsCache.get("all");
    }

    public CompletableFuture<List<SystemTool>> refreshTools() {
        return toolsCache.synchronous().refresh("all").thenApply(detected -> {
            updateCacheAndPublish(detected);
            return detected;
        });
    }

    public CompletableFuture<Boolean> isDockerAvailable() {
        return detectTools().thenApply(SystemToolService::isDockerDetected);
    }

    public CompletableFuture<Boolean> refreshDockerAvailable() {
        return refreshTools().thenApply(SystemToolService::isDockerDetected);
    }

    public boolean checkDockerAvailable() {
        List<SystemTool> tools = getCachedTools();
        return !tools.isEmpty() ? isDockerDetected(tools) : STATUS_DETECTED.equals(detectDockerTool().status());
    }

    public boolean checkRequiredToolsAvailable() {
        List<SystemTool> tools = getCachedTools();
        return areAllRequiredToolsDetected(!tools.isEmpty() ? tools : detectTools().join());
    }

    public static boolean areAllRequiredToolsDetected(List<SystemTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        Set<String> detectedNames = new HashSet<>();
        for (SystemTool tool : tools) {
            if (tool == null) {
                continue;
            }
            boolean detected = STATUS_DETECTED.equals(tool.status());
            if (tool.required() && !detected) {
                return false;
            }
            if (detected && tool.name() != null) {
                detectedNames.add(tool.name());
            }
        }
        return detectedNames.containsAll(SystemTool.REQUIRED_TOOL_NAMES);
    }

    static boolean isDockerDetected(List<SystemTool> tools) {
        return tools != null
                && tools.stream().anyMatch(t -> DOCKER.equals(t.name()) && STATUS_DETECTED.equals(t.status()));
    }

    private List<SystemTool> probeAllTools() {
        return List.of(detectTool(RIPGREP, "rg", ARG_VERSION), detectTool(GIT, "git", ARG_VERSION),
                detectTool(CTAGS, "ctags", ARG_VERSION), detectDockerTool());
    }

    private void updateCacheAndPublish(List<SystemTool> detected) {
        toolsCache.synchronous().put("all", detected);
        saveToDisk(detected);
        eventPublisher.publishEvent(new SystemToolsUpdatedEvent(detected, isDockerDetected(detected)));
    }

    Path getCacheFilePath() {
        Path homeDir = settingsService != null ? settingsService.getRoxyHomeDir() : null;
        return homeDir != null ? homeDir.resolve(SYSTEM_TOOLS_FILE) : null;
    }

    private List<SystemTool> loadFromDisk() {
        Path path = getCacheFilePath();
        if (path == null || !Files.exists(path))
            return List.of();
        try {
            SystemToolsCache cache = mapper.readValue(path.toFile(), SystemToolsCache.class);
            return (cache != null && cache.tools() != null) ? List.copyOf(cache.tools()) : List.of();
        } catch (IOException e) {
            log.warn("Failed to load cached system tools from {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private void saveToDisk(List<SystemTool> tools) {
        Path path = getCacheFilePath();
        if (path == null)
            return;
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
            String serverVersion = StringUtils.defaultIfBlank(info != null ? StringUtils.trim(info.version()) : null,
                    VERSION_UNKNOWN);
            String apiVersion = StringUtils.defaultIfBlank(info != null ? StringUtils.trim(info.apiVersion()) : null,
                    "");
            String versionDisplay = !apiVersion.isEmpty()
                    ? "Docker " + serverVersion + " (API " + apiVersion + ")"
                    : "Docker " + serverVersion;
            return SystemTool.ofKnownTool(DOCKER, versionDisplay, STATUS_DETECTED);
        } catch (Throwable t) {
            ExceptionUtils.rethrowIfCancelled(t, "Docker detection cancelled");
            String rootMessage = extractDiagnosticMessage(t);
            log.warn("Testcontainers Docker detection failed: {}", rootMessage);
            if (isGenericEnvironmentNotFound(rootMessage)
                    && (dockerCliPresentSupplier == null || !dockerCliPresentSupplier.getAsBoolean())) {
                return SystemTool.ofKnownTool(DOCKER, VERSION_NOT_FOUND, STATUS_MISSING);
            }
            return SystemTool.ofKnownTool(DOCKER, formatDockerErrorMessage(rootMessage), STATUS_ERROR);
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
        if (StringUtils.isBlank(message))
            return true;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("could not find a valid docker environment")
                || lower.contains("previous attempts to find a docker environment failed") || "not found".equals(lower);
    }

    private static String formatDockerErrorMessage(String rawMessage) {
        return StringUtils.isBlank(rawMessage)
                ? "Testcontainers connection failed"
                : StringUtils.abbreviate(StringUtils.normalizeSpace(rawMessage), MAX_DOCKER_ERROR_LENGTH);
    }

    private SystemTool detectTool(String name, String command, String versionArg) {
        ProcessResult result = ProcessRunner.run(List.of(command, versionArg));
        if (result.isSuccess()) {
            return SystemTool.ofKnownTool(name, parseVersion(result.output()), STATUS_DETECTED);
        }
        log.warn("Tool {} detection failed with exit code {}", name, result.exitCode());
        return SystemTool.ofKnownTool(name, VERSION_NOT_FOUND, STATUS_MISSING);
    }

    static String parseVersion(String output) {
        if (StringUtils.isBlank(output))
            return VERSION_UNKNOWN;
        String firstLine = output.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst()
                .orElse(VERSION_UNKNOWN);
        if (VERSION_UNKNOWN.equals(firstLine))
            return firstLine;
        for (String marker : VERSION_NOISE_MARKERS) {
            int idx = firstLine.indexOf(marker);
            if (idx > 0) {
                firstLine = firstLine.substring(0, idx).trim();
            }
        }
        return StringUtils.abbreviate(firstLine, MAX_VERSION_LENGTH);
    }
}
