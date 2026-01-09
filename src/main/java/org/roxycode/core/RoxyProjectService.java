package org.roxycode.core;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.roxycode.core.tools.ScriptServiceRegistry;
import org.roxycode.core.tools.ToolRegistry;
import org.roxycode.core.tools.service.FileSystemService;
import org.roxycode.core.tools.service.GitRunner;
import org.roxycode.core.utils.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    public static final String ROXY_HOME = "roxy_home";
    private static final String README_FILE = "README.md";

    private final Sandbox sandbox;
    private final SettingsService settingsService;
    private final FileSystemService fileSystemService;
    private final ToolRegistry toolRegistry;
    private final ScriptServiceRegistry scriptServiceRegistry;

    private String currentBranch = "";
    private RoxyMode currentMode = RoxyMode.PLAN;
    private String currentPlan = "";
    private Path roxyHome;

    @Inject
    public RoxyProjectService(Sandbox sandbox, FileSystemService fileSystemService,
                              SettingsService settingsService,
                              ToolRegistry toolRegistry, ScriptServiceRegistry scriptServiceRegistry) {
        this.sandbox = sandbox;
        this.fileSystemService = fileSystemService;
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.scriptServiceRegistry = scriptServiceRegistry;
    }

    @PostConstruct
    void init() {
        // we should be launched beside the roxy_home directory
        String launchedFrom = SystemUtils.getUserDir();
        LOG.info("RoxyProjectService launched from {}", launchedFrom);
        this.roxyHome = Path.of(launchedFrom).resolve(ROXY_HOME);
        LOG.info("RoxyProjectService roxy home {}", roxyHome);
        if (!this.roxyHome.toFile().exists()) {
            throw new IllegalStateException("The roxy home directory does not exist: " + this.roxyHome);
        }
        Path toolsHome = roxyHome.resolve("tools");
        LOG.info("RoxyProjectService loading tools from {}", toolsHome);
        toolRegistry.loadTools(toolsHome);
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

    public Path getRoxyWorkingDir() {
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
        if (!isValidFolder(newRoot)) {
            LOG.warn("Invalid project root: {}", newRoot);
            return;
        }
        sandbox.setRoot(newRoot);
        ensureProjectStructure();
        settingsService.setCurrentProject(getProjectRoot().toString());
        currentBranch = GitRunner.runGitCommand(sandbox.getRoot(), "branch", "--show-current").trim();
        currentPlan = "";
    }

    public boolean isValidFolder(Path path) {
        return Files.isDirectory(path) // Checks if it exists AND is a standard file (not a directory)
               && Files.isReadable(path)    // Checks if the JVM has read permissions
               && Files.isWritable(path);   // Checks if the JVM has write permissions
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

    public String getCurrentPlan() {
        return currentPlan;
    }

    public void setCurrentPlan(String currentPlan) {
        this.currentPlan = currentPlan;
    }

    public Path getRoxyHome() {
        return roxyHome;
    }

    public String getStaticSystemPrompt() {
        try {
            String agentsMd = FileUtils.readFileToString(roxyHome.resolve("AGENTS.md").toFile(), StandardCharsets.UTF_8);
            String toolApiDocs = scriptServiceRegistry.getApiDocs();
            return agentsMd + "\n\n" + toolApiDocs;
        } catch (Exception e) {
            return "You are RoxyCode, an AI coding assistant. ";
        }
    }

    public String getModeMessage(){
        return "IMPORTANT! Current Mode: " + currentMode + "\nPlease act in accordance with this mode.";
    }
}
