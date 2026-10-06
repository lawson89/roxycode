package org.roxycode.app.ai.services;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Service for interacting with arbitrary build tools via agents/colinxcode.toml.
 * Provides methods for compilation, testing, and cleaning.
 */
@Service
@AgentService("buildToolService")
public class GenericBuildToolService {

    private static final Logger log = LoggerFactory.getLogger(GenericBuildToolService.class);
    private static final String CONFIG_PATH = "agents/colinxcode.toml";

    private final ProjectService projectService;
    private final TomlMapper tomlMapper;

    @Autowired
    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper) {
        this.projectService = projectService;
        this.tomlMapper = tomlMapper;
        this.projectService.addProjectListener(path -> ensureConfig());
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    @AgentDoc("Cleans the project using the configured clean command")
    public BuildResult clean() {
        return runCommand("clean");
    }

    @AgentDoc("Compiles the project using the configured compile command")
    public BuildResult compile() {
        return runCommand("compile");
    }

    @AgentDoc("Checks if a generic build configuration exists (agents/colinxcode.toml)")
    public boolean detect() {
        if (!projectService.hasActiveProject()) {
            return false;
        }
        return Files.exists(projectService.getCurrentProjectRoot().resolve(CONFIG_PATH));
    }

    @AgentDoc("Runs all tests using the configured test command")
    public BuildResult runTests() {
        return runCommand("run_all");
    }

    @AgentDoc("Runs a single test using the configured run_single command. {testName} is replaced with the argument.")
    public BuildResult runSingleTest(String testName) {
        return runCommand("run_single", Map.of("testName", testName));
    }

    private BuildResult runCommand(String commandKey) {
        return runCommand(commandKey, Map.of());
    }

    private BuildResult runCommand(String commandKey, Map<String, String> placeholders) {
        if (!projectService.hasActiveProject()) {
            return new BuildResult(false, -1, "", List.of("No active project"), List.of(), "No active project");
        }

        ensureConfig();

        try {
            Map<String, Object> config = loadConfig();
            String command = getCommand(config, commandKey);
            if (command == null || command.isBlank()) {
                return new BuildResult(false, -1, "", List.of("Command not configured: " + commandKey), List.of(), "Command not configured");
            }

            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                command = command.replace("{" + entry.getKey() + "}", entry.getValue());
            }

            Map<String, String> env = getEnv(config);
            return execute(command, env);
        } catch (Exception e) {
            log.error("Failed to run build command: {}", commandKey, e);
            return new BuildResult(false, -1, "", List.of(e.getMessage()), List.of(), e.getMessage());
        }
    }

    private void ensureConfig() {
        Path configPath = projectService.getCurrentProjectRoot().resolve(CONFIG_PATH);
        if (Files.exists(configPath)) {
            return;
        }

        try {
            Files.createDirectories(configPath.getParent());
            String templateName = detectTemplate();
            try (InputStream is = getClass().getResourceAsStream("/templates/build/" + templateName + ".toml")) {
                if (is != null) {
                    Files.copy(is, configPath);
                } else {
                    Files.writeString(configPath, "[build]\ncompile = \"echo 'No build command'\"");
                }
            }
        } catch (IOException e) {
            log.error("Failed to initialize build config", e);
        }
    }

    private String detectTemplate() {
        Path root = projectService.getCurrentProjectRoot();
        if (Files.exists(root.resolve("pom.xml"))) {
            return "maven";
        }
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
            return "gradle";
        }
        if (Files.exists(root.resolve("package.json"))) {
            return "npm";
        }
        if (Files.exists(root.resolve("requirements.txt")) || Files.exists(root.resolve("pyproject.toml"))) {
            return "python";
        }
        return "default";
    }

    private Map<String, Object> loadConfig() throws IOException {
        Path configPath = projectService.getCurrentProjectRoot().resolve(CONFIG_PATH);
        return tomlMapper.readValue(configPath.toFile(), Map.class);
    }

    private String getCommand(Map<String, Object> config, String key) {
        if (key.equals("compile") || key.equals("clean")) {
            Map<String, Object> build = (Map<String, Object>) config.get("build");
            return build != null ? (String) build.get(key) : null;
        }
        if (key.equals("run_all") || key.equals("run_single")) {
            Map<String, Object> test = (Map<String, Object>) config.get("test");
            return test != null ? (String) test.get(key) : null;
        }
        return null;
    }

    private Map<String, String> getEnv(Map<String, Object> config) {
        Map<String, Object> envObj = (Map<String, Object>) config.get("env");
        if (envObj == null) {
            return Map.of();
        }
        Map<String, String> env = new HashMap<>();
        for (Map.Entry<String, Object> entry : envObj.entrySet()) {
            env.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return env;
    }

    private BuildResult execute(String command, Map<String, String> env) {
        log.info("Executing build command: {}", command);
        StringBuilder output = new StringBuilder();
        List<String> errors = new ArrayList<>();
        int exitCode = -1;

        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }

            if (env != null && !env.isEmpty()) {
                pb.environment().putAll(env);
            }

            pb.directory(projectService.getCurrentProjectRoot().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (process.waitFor(10, TimeUnit.MINUTES)) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                errors.add("Command timed out after 10 minutes");
            }

        } catch (Exception e) {
            log.error("Process execution failed", e);
            errors.add(e.getMessage());
        }

        boolean success = exitCode == 0 && errors.isEmpty();
        return new BuildResult(success, exitCode, output.toString(), errors, List.of(), output.toString());
    }

    public String getConfigContent() throws IOException {
        if (!projectService.hasActiveProject()) {
            return "";
        }
        Path configPath = projectService.getCurrentProjectRoot().resolve(CONFIG_PATH);
        if (!Files.exists(configPath)) {
            ensureConfig();
        }
        return Files.readString(configPath, StandardCharsets.UTF_8);
    }

    public void saveConfigContent(String content) throws IOException {
        if (!projectService.hasActiveProject()) {
            return;
        }
        Path configPath = projectService.getCurrentProjectRoot().resolve(CONFIG_PATH);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, content, StandardCharsets.UTF_8);
    }
}