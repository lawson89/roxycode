package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String PROJECTS_SUBDIR = "projects";
    private static final int MAX_PROJECT_NAME_LENGTH = 40;
    static final String PROJECT_CONFIG_FILE = "project.toml";
    static final String ROXYCODE_TMP_DIR = ".roxycode/tmp";
    private static final String ROXYCODE_TMP_PREFIX = ".roxycode/tmp/";
    private static final String DATA_TMP_DIR = "tmp";
    private static final String NO_PROJECT_ROOT_MSG = "Project root is not set.";

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectConfig(String geminiModel, boolean buildConfirmed) {
        public static final ProjectConfig DEFAULT = new ProjectConfig("", false);

        public ProjectConfig {
            geminiModel = StringUtils.trimToEmpty(geminiModel);
        }

        public ProjectConfig(String geminiModel) {
            this(geminiModel, false);
        }
    }

    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private volatile Path currentProjectRoot;

    public ProjectService(SettingsService settingsService, ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.eventPublisher = Objects.requireNonNullElse(eventPublisher, event -> {
        });
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        if (StringUtils.isNotEmpty(savedPath)) {
            this.currentProjectRoot = Paths.get(savedPath);
        }
    }

    public Path getCurrentProjectRoot() {
        return currentProjectRoot;
    }

    public boolean hasActiveProject() {
        return currentProjectRoot != null;
    }

    public void setCurrentProjectRoot(Path newRoot) {
        if (newRoot != null) {
            this.currentProjectRoot = newRoot;
            settingsService.getSettings().setCurrentProjectPath(newRoot.toAbsolutePath().toString());
            settingsService.saveSettings();
            eventPublisher.publishEvent(new ProjectChangedEvent(newRoot));
        }
    }

    public String getProjectName() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            return "No Project";
        }
        Path fileName = projectRoot.getFileName();
        return Objects.requireNonNullElse(fileName, projectRoot).toString();
    }

    private ProjectConfig readProjectConfig() {
        if (!hasActiveProject()) {
            return ProjectConfig.DEFAULT;
        }
        return PathUtils.readTomlOrDefault(resolveProjectDataPath(PROJECT_CONFIG_FILE), ProjectConfig.class,
                ProjectConfig.DEFAULT, AiConfig.SHARED_TOML_MAPPER);
    }

    private void writeProjectConfig(ProjectConfig config) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONFIG_FILE);
        try {
            PathUtils.writeToml(configFile, config, AiConfig.SHARED_TOML_MAPPER);
        } catch (IOException e) {
            log.error("Failed to save project config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project config", e);
        }
    }

    public String getProjectGeminiModel() {
        return readProjectConfig().geminiModel();
    }

    public void setProjectGeminiModel(String geminiModel) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(StringUtils.trimToEmpty(geminiModel), existing.buildConfirmed()));
        AppSettings currentSettings = null;
        if (settingsService != null && settingsService.getSettings() != null) {
            currentSettings = settingsService.getSettings().copy();
        }
        eventPublisher.publishEvent(new SettingsChangedEvent(currentSettings));
    }

    public boolean isBuildConfirmed() {
        return readProjectConfig().buildConfirmed();
    }

    public void setBuildConfirmed(boolean confirmed) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(existing.geminiModel(), confirmed));
    }

    public String getEffectiveGeminiModel() {
        String projectModel = getProjectGeminiModel();
        if (!projectModel.isEmpty()) {
            return projectModel;
        }
        return SettingsService.resolveGlobalGeminiModel(settingsService);
    }

    public String getNormalizedProjectPath() {
        return resolveNormalizedProjectPath(this);
    }

    public static String resolveNormalizedProjectPath(ProjectService projectService) {
        if (projectService == null || projectService.getCurrentProjectRoot() == null) {
            return "";
        }
        return projectService.getCurrentProjectRoot().toAbsolutePath().normalize().toString();
    }

    public Path resolvePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        if (relativePath != null) {
            String normalized = PathUtils.normalizeRelativePath(relativePath.trim());
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            if (ROXYCODE_TMP_DIR.equals(normalized) || normalized.startsWith(ROXYCODE_TMP_PREFIX)) {
                Path tmpRoot = resolveProjectDataPath(DATA_TMP_DIR);
                String subPath = StringUtils.defaultIfEmpty(StringUtils.removeStart(normalized, ROXYCODE_TMP_PREFIX),
                        ".");
                if (ROXYCODE_TMP_DIR.equals(normalized)) {
                    subPath = ".";
                }
                return resolveWithinSandbox(tmpRoot, subPath);
            }
        }
        return resolveWithinSandbox(projectRoot, relativePath);
    }

    public String resolveRelativePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        String target = StringUtils.defaultIfBlank(relativePath, ".").trim();
        Path resolved = resolveWithinSandbox(root, target);
        return StringUtils.defaultIfEmpty(PathUtils.normalizeRelativePath(root.relativize(resolved).toString()), ".");
    }

    public Path getProjectDataDir() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String dirName = sanitizeProjectName(normalizedRoot) + "-" + computeProjectHash(normalizedRoot);
        Path roxyHome = Objects.requireNonNullElseGet(settingsService.getRoxyHomeDir(),
                SettingsService::getDefaultRoxyHomeDir);
        return roxyHome.toAbsolutePath().normalize().resolve(PROJECTS_SUBDIR).resolve(dirName);
    }

    public Path resolveProjectDataPath(String relativePath) {
        return resolveWithinSandbox(getProjectDataDir(), relativePath);
    }

    private static Path resolveWithinSandbox(Path sandboxRoot, String relativePath) {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(Objects.requireNonNullElse(relativePath, ".")).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    public static String sanitizeProjectName(Path projectRoot) {
        String rawName = "";
        if (projectRoot != null && projectRoot.getFileName() != null) {
            rawName = projectRoot.getFileName().toString();
        }
        return PathUtils.sanitizeSlug(rawName, MAX_PROJECT_NAME_LENGTH, "project");
    }

    static String computeProjectHash(Path normalizedRoot) {
        try {
            byte[] hashBytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedRoot.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
