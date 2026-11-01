package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
@SuppressWarnings("PMD.NullAssignment")
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String PROJECTS_SUBDIR = "projects";
    private static final int MAX_PROJECT_NAME_LENGTH = 40;
    static final String PROJECT_CONFIG_FILE = "project.toml";
    static final String ROXYCODE_TMP_DIR = ".roxycode/tmp";
    private static final String ROXYCODE_TMP_PREFIX = ".roxycode/tmp/";
    private static final String DATA_TMP_DIR = "tmp";

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectConfig(String geminiModel, boolean buildConfirmed) {
        public ProjectConfig {
            if (geminiModel == null) {
                geminiModel = "";
            } else {
                geminiModel = geminiModel.trim();
            }
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
        if (eventPublisher != null) {
            this.eventPublisher = eventPublisher;
        } else {
            this.eventPublisher = event -> {
            };
        }
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        if (savedPath != null && !savedPath.isEmpty()) {
            this.currentProjectRoot = Paths.get(savedPath);
        } else {
            this.currentProjectRoot = null;
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
            AppSettings settings = settingsService.getSettings();
            settings.setCurrentProjectPath(newRoot.toAbsolutePath().toString());
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
        if (fileName != null) {
            return fileName.toString();
        }
        return projectRoot.toString();
    }

    private ProjectConfig readProjectConfig() {
        if (!hasActiveProject()) {
            return new ProjectConfig("", false);
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONFIG_FILE);
        if (configFile == null || !Files.exists(configFile)) {
            return new ProjectConfig("", false);
        }
        try {
            ProjectConfig config = AiConfig.SHARED_TOML_MAPPER.readValue(configFile.toFile(), ProjectConfig.class);
            if (config != null) {
                return config;
            }
            return new ProjectConfig("", false);
        } catch (IOException e) {
            log.warn("Failed to read project config {}: {}", configFile, e.getMessage());
            return new ProjectConfig("", false);
        }
    }

    private void writeProjectConfig(ProjectConfig config) {
        if (!hasActiveProject()) {
            throw new IllegalStateException("Project root is not set.");
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONFIG_FILE);
        try {
            PathUtils.createParentDirectories(configFile);
            AiConfig.SHARED_TOML_MAPPER.writeValue(configFile.toFile(), config);
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
            throw new IllegalStateException("Project root is not set.");
        }
        String normalized = "";
        if (geminiModel != null) {
            normalized = geminiModel.trim();
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(normalized, existing.buildConfirmed()));
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
            throw new IllegalStateException("Project root is not set.");
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
        if (projectService == null) {
            return "";
        }
        Path projectRoot = projectService.getCurrentProjectRoot();
        if (projectRoot == null) {
            return "";
        }
        return projectRoot.toAbsolutePath().normalize().toString();
    }

    public Path resolvePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException("Project root is not set.");
        }
        if (relativePath != null) {
            String normalized = PathUtils.normalizeRelativePath(relativePath.trim());
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            if (normalized.equals(ROXYCODE_TMP_DIR) || normalized.startsWith(ROXYCODE_TMP_PREFIX)) {
                Path tmpRoot = resolveProjectDataPath(DATA_TMP_DIR);
                String subPath = ".";
                if (normalized.startsWith(ROXYCODE_TMP_PREFIX)) {
                    subPath = normalized.substring(ROXYCODE_TMP_PREFIX.length());
                }
                return resolveWithinSandbox(tmpRoot, subPath);
            }
        }
        return resolveWithinSandbox(projectRoot, relativePath);
    }

    public String resolveRelativePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException("Project root is not set.");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        String target = ".";
        if (relativePath != null && !relativePath.isBlank()) {
            target = relativePath.trim();
        }
        Path resolved = resolveWithinSandbox(root, target);
        String rel = PathUtils.normalizeRelativePath(root.relativize(resolved).toString());
        if (rel.isEmpty()) {
            return ".";
        }
        return rel;
    }

    public Path getProjectDataDir() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException("Project root is not set.");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String dirName = sanitizeProjectName(normalizedRoot) + "-" + computeProjectHash(normalizedRoot);
        Path roxyHome = settingsService.getRoxyHomeDir();
        if (roxyHome == null) {
            roxyHome = SettingsService.getDefaultRoxyHomeDir();
        }
        return roxyHome.toAbsolutePath().normalize().resolve(PROJECTS_SUBDIR).resolve(dirName);
    }

    public Path resolveProjectDataPath(String relativePath) {
        return resolveWithinSandbox(getProjectDataDir(), relativePath);
    }

    private static Path resolveWithinSandbox(Path sandboxRoot, String relativePath) {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        String target = ".";
        if (relativePath != null) {
            target = relativePath;
        }
        Path resolved = root.resolve(target).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    public static String sanitizeProjectName(Path projectRoot) {
        Path fileName = null;
        if (projectRoot != null) {
            fileName = projectRoot.getFileName();
        }
        String rawName = "";
        if (fileName != null) {
            rawName = fileName.toString();
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
