package org.roxycode.core;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.tools.service.FileSystemService;
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
    public static final String ROXY_PROJECT_DIR = "roxy";
    public static final String ROXY_PROJECT_CACHE = ".cache";
    private static final String README_FILE = "README.md";

    private final Sandbox sandbox;
    private final FileSystemService fileSystemService;

    @Inject
    public RoxyProjectService(Sandbox sandbox, FileSystemService fileSystemService) {
        this.sandbox = sandbox;
        this.fileSystemService = fileSystemService;
    }

    public void ensureProjectStructure() {
        LOG.info("Ensuring Roxy project structure...");
        try {
            Path projectDir = getRoxyProjectDir();
            
            if (!Files.exists(projectDir)) {
                LOG.info("Creating {} directory.", ROXY_PROJECT_DIR);
                Files.createDirectories(projectDir);
            }

            Path readmePath = projectDir.resolve(README_FILE);
            if (!Files.exists(readmePath)) {
                LOG.info("Creating {}/{}", ROXY_PROJECT_DIR, README_FILE);
                String defaultContent = "# Roxy Project\n\nThis is the root of your Roxy project.";
                fileSystemService.writeFile(ROXY_PROJECT_DIR + "/" + README_FILE, defaultContent);
            }
        } catch (IOException e) {
            LOG.error("Failed to ensure project structure", e);
            throw new RuntimeException("Failed to ensure Roxy project structure", e);
        }
    }

    public Path getRoxyProjectDir(){
        Path projectDir = sandbox.resolve(ROXY_PROJECT_DIR);
        return  projectDir;
    }

    public Path getRoxyProjectCacheDir() throws IOException {
        Path cacheDir = getRoxyProjectDir().resolve(ROXY_PROJECT_CACHE);
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }
        return cacheDir;
    }
}
