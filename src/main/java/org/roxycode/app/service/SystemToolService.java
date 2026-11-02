package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
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
public class SystemToolService {
    private static final Logger log = LoggerFactory.getLogger(SystemToolService.class);
    static final String SYSTEM_TOOLS_FILE = "system-tools.toml";
    private static final String CACHE_KEY_ALL = "all";
    private static final String RIPGREP = SystemTool.TOOL_RIPGREP;
    private static final String GIT = SystemTool.TOOL_GIT;
    private static final String CTAGS = SystemTool.TOOL_CTAGS;
    private static final String DOCKER = SystemTool.TOOL_DOCKER;
    private static final String BUBBLEWRAP = SystemTool.TOOL_BUBBLEWRAP;
    private static final String SEATBELT = SystemTool.TOOL_SEATBELT;
    static final Path MACOS_SANDBOX_EXEC_PATH = Path.of("/usr/bin/sandbox-exec");
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
        this.mapper = Objects.requireNonNullElse(mapper, AiConfig.SHARED_TOML_MAPPER);
        this.eventPublisher = Objects.requireNonNullElse(eventPublisher, event -> {
        });
        this.dockerProbe = dockerProbe;
        this.dockerCliPresentSupplier = dockerCliPresentSupplier;
        this.toolProber = this::probeAllTools;
        this.toolsCache = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES)
                .buildAsync((key, executor) -> CompletableFuture.supplyAsync(this::loadOrProbe, executor));
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
        this.toolProber = Objects.requireNonNullElse(toolProber, this::probeAllTools);
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingThrowable"})
    static DockerVersionInfo probeWithTestcontainers() {
        resetTestcontainersFailureCache();
        try {
            DockerClient client = DockerClientFactory.instance().client();
            Version version = client.versionCmd().exec();
            if (version == null) {
                return new DockerVersionInfo(null, null);
            }
            return new DockerVersionInfo(version.getVersion(), version.getApiVersion());
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

    public List<SystemTool> getCachedTools() {
        CompletableFuture<List<SystemTool>> future = toolsCache.getIfPresent(CACHE_KEY_ALL);
        if (future != null && future.isDone()) {
            return future.getNow(List.of());
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return List.of();
        }
        List<SystemTool> loaded = loadFromDisk();
        if (!loaded.isEmpty()) {
            toolsCache.synchronous().put(CACHE_KEY_ALL, loaded);
            return loaded;
        }
        return List.of();
    }

    public CompletableFuture<List<SystemTool>> detectTools() {
        List<SystemTool> cached = getCachedTools();
        if (!cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        return toolsCache.get(CACHE_KEY_ALL);
    }

    public CompletableFuture<List<SystemTool>> refreshTools() {
        Path path = getCacheFilePath();
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.debug("Failed to delete cache file for refresh", e);
            }
        }
        return toolsCache.synchronous().refresh(CACHE_KEY_ALL).thenApply(tools -> {
            toolsCache.put(CACHE_KEY_ALL, CompletableFuture.completedFuture(tools));
            return tools;
        });
    }

    public CompletableFuture<Boolean> isDockerAvailable() {
        return detectTools().thenApply(SystemToolService::isDockerDetected);
    }

    public boolean checkDockerAvailable() {
        List<SystemTool> tools = getCachedTools();
        if (!tools.isEmpty()) {
            return isDockerDetected(tools);
        }
        return STATUS_DETECTED.equals(detectDockerTool().status());
    }

    public boolean checkNativeSandboxAvailable() {
        try {
            return isNativeSandboxDetected(detectTools().join());
        } catch (Exception e) {
            log.debug("Failed to check native sandbox availability: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isNativeSandboxDetected(List<SystemTool> tools) {
        if (tools == null) {
            return false;
        }
        for (SystemTool tool : tools) {
            if (tool == null) {
                continue;
            }
            if ((BUBBLEWRAP.equals(tool.name()) || SEATBELT.equals(tool.name()))
                    && STATUS_DETECTED.equals(tool.status())) {
                return true;
            }
        }
        return false;
    }

    public boolean checkGitAvailable() {
        List<SystemTool> tools = getCachedTools();
        if (!tools.isEmpty()) {
            return isGitDetected(tools);
        }
        return STATUS_DETECTED.equals(detectTool(GIT, "git", ARG_VERSION).status());
    }

    public static boolean isGitDetected(List<SystemTool> tools) {
        return tools != null
                && tools.stream().anyMatch(t -> GIT.equals(t.name()) && STATUS_DETECTED.equals(t.status()));
    }

    public static boolean areAllRequiredToolsDetected(List<SystemTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        return getMissingRequiredToolNames(tools).isEmpty();
    }

    public static List<String> getMissingRequiredToolNames(List<SystemTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return SystemTool.REQUIRED_TOOL_NAMES;
        }
        Set<String> detectedNames = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (SystemTool tool : tools) {
            if (tool == null) {
                continue;
            }
            boolean detected = STATUS_DETECTED.equals(tool.status());
            if (detected && tool.name() != null) {
                detectedNames.add(tool.name());
            } else if (tool.required() && tool.name() != null && !missing.contains(tool.name())) {
                missing.add(tool.name());
            }
        }
        for (String reqName : SystemTool.REQUIRED_TOOL_NAMES) {
            if (!detectedNames.contains(reqName) && !missing.contains(reqName)) {
                missing.add(reqName);
            }
        }
        return List.copyOf(missing);
    }

    static boolean isDockerDetected(List<SystemTool> tools) {
        return tools != null
                && tools.stream().anyMatch(t -> DOCKER.equals(t.name()) && STATUS_DETECTED.equals(t.status()));
    }

    private List<SystemTool> probeAllTools() {
        List<SystemTool> tools = new ArrayList<>();
        tools.add(detectTool(RIPGREP, "rg", ARG_VERSION));
        tools.add(detectTool(CTAGS, "ctags", ARG_VERSION));
        tools.add(detectTool(GIT, "git", ARG_VERSION));
        tools.add(detectDockerTool());
        if (SystemUtils.IS_OS_LINUX) {
            tools.add(detectBubblewrapTool());
        } else if (SystemUtils.IS_OS_MAC) {
            tools.add(detectSeatbeltTool());
        }
        return List.copyOf(tools);
    }

    SystemTool detectBubblewrapTool() {
        return detectTool(BUBBLEWRAP, "bwrap", ARG_VERSION);
    }

    SystemTool detectSeatbeltTool() {
        if (Files.isExecutable(MACOS_SANDBOX_EXEC_PATH)) {
            return SystemTool.ofKnownTool(SEATBELT, "Built-in (macOS)", STATUS_DETECTED);
        }
        return SystemTool.ofKnownTool(SEATBELT, VERSION_NOT_FOUND, STATUS_MISSING);
    }

    Path getCacheFilePath() {
        if (settingsService == null || settingsService.getRoxyHomeDir() == null) {
            return null;
        }
        return settingsService.getRoxyHomeDir().resolve(SYSTEM_TOOLS_FILE);
    }

    private List<SystemTool> loadFromDisk() {
        SystemToolsCache cache = PathUtils.readTomlOrDefault(getCacheFilePath(), SystemToolsCache.class, null, mapper);
        if (cache != null && cache.getTools() != null) {
            return List.copyOf(cache.getTools());
        }
        return List.of();
    }

    private void saveToDisk(List<SystemTool> tools) {
        Path path = getCacheFilePath();
        if (path != null) {
            try {
                PathUtils.writeToml(path, new SystemToolsCache(tools), mapper);
            } catch (IOException e) {
                log.warn("Failed to save system tools cache to {}: {}", path, e.getMessage());
            }
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    SystemTool detectDockerTool() {
        try {
            DockerVersionInfo info = dockerProbe.probe();
            String rawVersion = null;
            String rawApiVersion = null;
            if (info != null) {
                rawVersion = StringUtils.trim(info.version());
                rawApiVersion = StringUtils.trim(info.apiVersion());
            }
            String serverVersion = StringUtils.defaultIfBlank(rawVersion, VERSION_UNKNOWN);
            String apiVersion = StringUtils.defaultIfBlank(rawApiVersion, "");
            String versionDisplay = "Docker " + serverVersion;
            if (!apiVersion.isEmpty()) {
                versionDisplay = versionDisplay + " (API " + apiVersion + ")";
            }
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
        if (root != null) {
            return ExceptionUtils.getMessageOrSimpleName(root);
        }
        return ExceptionUtils.getMessageOrSimpleName(t);
    }

    private static boolean isGenericEnvironmentNotFound(String message) {
        if (StringUtils.isBlank(message)) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("could not find a valid docker environment")
                || lower.contains("previous attempts to find a docker environment failed") || "not found".equals(lower);
    }

    private static String formatDockerErrorMessage(String rawMessage) {
        if (StringUtils.isBlank(rawMessage)) {
            return "Testcontainers connection failed";
        }
        return StringUtils.abbreviate(StringUtils.normalizeSpace(rawMessage), MAX_DOCKER_ERROR_LENGTH);
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
        if (StringUtils.isBlank(output)) {
            return VERSION_UNKNOWN;
        }
        String firstLine = output.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst()
                .orElse(VERSION_UNKNOWN);
        if (VERSION_UNKNOWN.equals(firstLine)) {
            return firstLine;
        }
        for (String marker : VERSION_NOISE_MARKERS) {
            int idx = firstLine.indexOf(marker);
            if (idx > 0) {
                firstLine = firstLine.substring(0, idx).trim();
            }
        }
        return StringUtils.abbreviate(firstLine, MAX_VERSION_LENGTH);
    }
}
