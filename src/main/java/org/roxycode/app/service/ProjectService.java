package org.roxycode.app.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Service for managing the active project root directory. */
@Service
@SuppressWarnings("PMD.NullAssignment")
public class ProjectService {
    private static final String PROJECTS_SUBDIR = "projects";
    private static final int MAX_PROJECT_NAME_LENGTH = 40;

    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private volatile Path currentProjectRoot;

    public ProjectService(
            SettingsService settingsService, ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {};
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        if (savedPath != null && !savedPath.isEmpty()) {
            this.currentProjectRoot = Paths.get(savedPath);
        } else {
            // Fix: Do not default to user.dir to avoid accidental indexing of home
            // directory
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
        return fileName != null ? fileName.toString() : projectRoot.toString();
    }

    /**
     * Resolves a path relative to the active project root and validates that it stays within the
     * project sandbox.
     *
     * @param relativePath the path relative to the project root (null defaults to ".")
     * @return the normalized absolute path within the project root
     * @throws IllegalStateException if no active project root is set
     * @throws SecurityException if the resolved path escapes the project root
     */
    public Path resolvePath(String relativePath) {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException("Project root is not set.");
        }
        return resolveWithinSandbox(projectRoot, relativePath);
    }

    /**
     * Returns the per-project data directory under {@code <roxyHomeDir>/projects/<name>-<hash>} for
     * the active project.
     *
     * @return the normalized absolute path to the project data directory
     * @throws IllegalStateException if no active project root is set
     */
    public Path getProjectDataDir() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException("Project root is not set.");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String dirName =
                sanitizeProjectDirName(normalizedRoot) + "-" + computeProjectHash(normalizedRoot);
        Path roxyHome = settingsService.getRoxyHomeDir();
        if (roxyHome == null) {
            roxyHome = SettingsService.getDefaultRoxyHomeDir();
        }
        return roxyHome.toAbsolutePath().normalize().resolve(PROJECTS_SUBDIR).resolve(dirName);
    }

    /**
     * Resolves a path relative to the per-project data directory ({@link #getProjectDataDir()}) and
     * validates that it stays within that directory sandbox.
     *
     * @param relativePath the path relative to the project data directory (null defaults to ".")
     * @return the normalized absolute path within the project data directory
     * @throws IllegalStateException if no active project root is set
     * @throws SecurityException if the resolved path escapes the project data directory
     */
    public Path resolveProjectDataPath(String relativePath) {
        return resolveWithinSandbox(getProjectDataDir(), relativePath);
    }

    private static Path resolveWithinSandbox(Path sandboxRoot, String relativePath) {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path resolved =
                root.resolve(relativePath != null ? relativePath : ".")
                        .toAbsolutePath()
                        .normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    public static String sanitizeProjectName(Path projectRoot) {
        Path fileName = projectRoot != null ? projectRoot.getFileName() : null;
        String raw = fileName != null ? fileName.toString().toLowerCase(Locale.ROOT) : "";
        String sanitized = raw.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (sanitized.length() > MAX_PROJECT_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_PROJECT_NAME_LENGTH).replaceAll("-+$", "");
        }
        return sanitized.isEmpty() ? "project" : sanitized;
    }

    static String sanitizeProjectDirName(Path normalizedRoot) {
        return sanitizeProjectName(normalizedRoot);
    }

    static String computeProjectHash(Path normalizedRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes =
                    digest.digest(normalizedRoot.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
