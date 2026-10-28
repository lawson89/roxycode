package org.roxycode.app.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.util.PathUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class ProjectService {
    private static final String PROJECTS_SUBDIR = "projects";
    private static final int MAX_PROJECT_NAME_LENGTH = 40;

    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private volatile Path currentProjectRoot;

    public ProjectService(SettingsService settingsService, ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {
        };
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        this.currentProjectRoot = (savedPath != null && !savedPath.isEmpty()) ? Paths.get(savedPath) : null;
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
        if (projectRoot == null)
            return "No Project";
        Path fileName = projectRoot.getFileName();
        return fileName != null ? fileName.toString() : projectRoot.toString();
    }

    public Path resolvePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null)
            throw new IllegalStateException("Project root is not set.");
        return resolveWithinSandbox(projectRoot, relativePath);
    }

    public String resolveRelativePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null)
            throw new IllegalStateException("Project root is not set.");
        Path root = projectRoot.toAbsolutePath().normalize();
        String target = (relativePath == null || relativePath.isBlank()) ? "." : relativePath.trim();
        Path resolved = resolveWithinSandbox(root, target);
        String rel = root.relativize(resolved).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    public Path getProjectDataDir() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null)
            throw new IllegalStateException("Project root is not set.");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String dirName = sanitizeProjectName(normalizedRoot) + "-" + computeProjectHash(normalizedRoot);
        Path roxyHome = settingsService.getRoxyHomeDir();
        if (roxyHome == null)
            roxyHome = SettingsService.getDefaultRoxyHomeDir();
        return roxyHome.toAbsolutePath().normalize().resolve(PROJECTS_SUBDIR).resolve(dirName);
    }

    public Path resolveProjectDataPath(String relativePath) {
        return resolveWithinSandbox(getProjectDataDir(), relativePath);
    }

    private static Path resolveWithinSandbox(Path sandboxRoot, String relativePath) {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath != null ? relativePath : ".").toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    public static String sanitizeProjectName(Path projectRoot) {
        Path fileName = projectRoot != null ? projectRoot.getFileName() : null;
        return PathUtils.sanitizeSlug(fileName != null ? fileName.toString() : "", MAX_PROJECT_NAME_LENGTH, "project");
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
