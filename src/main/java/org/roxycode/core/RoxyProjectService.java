package org.roxycode.core;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.tools.service.FileSystemService;
import org.roxycode.core.tools.service.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RoxyProjectService manages the structure and paths of the Roxy project within the sandbox.
 * It serves as the central source of truth for a project being worked on by RoxyCode.
 * It holds the current path and mode of operation.
 */
@Singleton
public class RoxyProjectService {
    private static final Logger LOG = LoggerFactory.getLogger(RoxyProjectService.class);
    public static final String ROXY_WORKING_DIR = "roxy";
    public static final String ROXY_CACHE = ".cache";
    private static final String README_FILE = "README.md";

    private final Sandbox sandbox;
    private final SettingsService settingsService;
    private final FileSystemService fileSystemService;
    private final GitService gitService;

    private String currentBranch = "";
    private RoxyMode currentMode = RoxyMode.DISCOVER;

    @Inject
    public RoxyProjectService(Sandbox sandbox, FileSystemService fileSystemService,
                              SettingsService settingsService, GitService gitService) {
        this.sandbox = sandbox;
        this.fileSystemService = fileSystemService;
        this.settingsService = settingsService;
        this.gitService = gitService;
    }

    public void ensureProjectStructure() {
        LOG.info("Ensuring Roxy project structure...");
        try {
            Path projectDir = getRoxyWorkingDir();
            
            if (!Files.exists(projectDir)) {
                LOG.info("Creating {} directory.", ROXY_WORKING_DIR);
                Files.createDirectories(projectDir);
            }

            Path readmePath = projectDir.resolve(README_FILE);
            if (!Files.exists(readmePath)) {
                LOG.info("Creating {}/{}", ROXY_WORKING_DIR, README_FILE);
                String defaultContent = "# Roxy Project\n\nThis is the root of your Roxy project.";
                fileSystemService.writeFile(ROXY_WORKING_DIR + "/" + README_FILE, defaultContent);
            }
        } catch (IOException e) {
            LOG.error("Failed to ensure project structure", e);
            throw new RuntimeException("Failed to ensure Roxy project structure", e);
        }
    }

    public Path getRoxyWorkingDir(){
        return sandbox.resolve(ROXY_WORKING_DIR);
    }

    public Path getRoxyCacheDir() throws IOException {
        Path cacheDir = getRoxyWorkingDir().resolve(ROXY_CACHE);
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }
        return cacheDir;
    }

    public Path getProjectRoot() {
        return sandbox.getRoot().toAbsolutePath().normalize();
    }

    public void changeProjectRoot(Path newRoot) {
        LOG.info("Changing project root to: {}", newRoot);
        sandbox.setRoot(newRoot);
        ensureProjectStructure();
        settingsService.setCurrentProject(getProjectRoot().toString());
        currentBranch = gitService.getCurrentBranch(getProjectRoot().toString());
    }

    public String getCurrentBranch() {
        return currentBranch;
    }

    public RoxyMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(RoxyMode currentMode) {
        this.currentMode = currentMode;
    }
}
