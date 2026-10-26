package org.roxycode.app.ai.services;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.text.StringSubstitutor;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.services.BuildContainerService.ContainerConfig;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * Service for interacting with arbitrary build tools via per-project build.toml. Provides methods
 * for compilation, testing, and cleaning, either on the host or inside a warm reusable
 * Testcontainers Docker container managed by {@link BuildContainerService}.
 */
@AgentService(
        value = "buildToolService",
        phases = {"CODE", "EXPLORE", "PLAN", "REVIEW"})
public class GenericBuildToolService {

    private static final Logger log = LoggerFactory.getLogger(GenericBuildToolService.class);
    private static final String CONFIG_FILE = "build.toml";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

    private final ProjectService projectService;
    private final TomlMapper tomlMapper;
    private final BuildContainerService buildContainerService;

    public GenericBuildToolService(
            ProjectService projectService,
            TomlMapper tomlMapper,
            BuildContainerService buildContainerService) {
        this.projectService = projectService;
        this.tomlMapper = tomlMapper;
        this.buildContainerService = buildContainerService;
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    @EventListener
    public void handleProjectChanged(ProjectChangedEvent event) {
        stopActiveContainer();
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    public void stopActiveContainer() {
        buildContainerService.stopActiveContainer();
    }

    @AgentDoc(
            value = "Cleans the project using the configured clean command",
            examples = "buildToolService.clean()")
    public BuildResult clean() {
        return runCommand("build", "clean");
    }

    @AgentDoc(
            value = "Compiles the project using the configured compile command",
            examples = "buildToolService.compile()")
    public BuildResult compile() {
        return runCommand("build", "compile");
    }

    @AgentDoc(
            value = "Checks if a generic build configuration exists (build.toml)",
            examples = "buildToolService.detect()")
    public boolean detect() {
        if (!projectService.hasActiveProject()) {
            return false;
        }
        return Files.exists(getConfigPath());
    }

    @AgentDoc(
            value = "Alias for runTests(). Runs all tests using the configured test command.",
            examples = "buildToolService.test()")
    public BuildResult test() {
        return runTests();
    }

    @AgentDoc(
            value = "Runs all tests using the configured test command",
            examples = "buildToolService.runTests()")
    public BuildResult runTests() {
        return runCommand("test", "run_all");
    }

    @AgentDoc(
            value =
                    "Runs a single test using the configured run_single command. {testName} is replaced with the argument.",
            examples = "buildToolService.runSingleTest('com.example.TestClass')")
    public BuildResult runSingleTest(
            @AgentDoc("The name of the test class or method (e.g., MyTest or MyTest#myMethod)")
                    String testName) {
        return runCommand("test", "run_single", Map.of("testName", testName));
    }

    private BuildResult runCommand(String section, String commandKey) {
        return runCommand(section, commandKey, Map.of());
    }

    @AgentDoc(
            value =
                    "Compiles the project and, if successful, runs all tests. Use this for the Verification phase.",
            examples = "buildToolService.buildAndTest()")
    public BuildResult buildAndTest() {
        BuildResult compileResult = compile();
        if (!compileResult.success()) {
            return compileResult; // Fast fail if compilation breaks
        }
        BuildResult testResult = runTests();
        // Combine outputs for the LLM
        return new BuildResult(
                testResult.success(),
                testResult.exitCode(),
                """
                COMPILE OUTPUT:
                %s

                TEST OUTPUT:
                %s"""
                        .formatted(compileResult.output(), testResult.output()),
                testResult.errors(),
                testResult.failedTests(),
                testResult.log());
    }

    private BuildResult runCommand(
            String section, String commandKey, Map<String, String> placeholders) {
        if (!projectService.hasActiveProject()) {
            return new BuildResult(
                    false, -1, "", List.of("No active project"), List.of(), "No active project");
        }

        ensureConfig();

        try {
            Map<String, Object> config = loadConfig();
            String command = getCommand(config, section, commandKey);
            if (command == null || command.isBlank()) {
                return new BuildResult(
                        false,
                        -1,
                        "",
                        List.of("Command not configured: " + commandKey),
                        List.of(),
                        "Command not configured");
            }

            if (!placeholders.isEmpty()) {
                command = new StringSubstitutor(placeholders, "{", "}").replace(command);
            }

            String errorRegex = getParseRegex(config, "error_regex");
            String testFailureRegex = getParseRegex(config, "test_failure_regex");

            Map<String, String> env = getEnv(config);
            ContainerConfig containerConfig = getContainerConfig(config);
            if (containerConfig.enabled()) {
                return executeInContainer(
                        command, containerConfig, env, errorRegex, testFailureRegex);
            } else {
                stopActiveContainer();
                return execute(command, env, errorRegex, testFailureRegex);
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to run build command: {}", commandKey, e);
            return new BuildResult(
                    false, -1, "", List.of(e.getMessage()), List.of(), e.getMessage());
        }
    }

    private Path getConfigPath() {
        return projectService.resolveProjectDataPath(CONFIG_FILE);
    }

    private static final String DEFAULT_FALLBACK_TOML =
            """
            [build]
            compile = 'echo No build command'""";

    private String loadDefaultTemplateContent() {
        String templateName = detectTemplate();
        try (InputStream is =
                getClass().getResourceAsStream("/templates/build/" + templateName + ".toml")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to read build template: {}", templateName, e);
        }
        return DEFAULT_FALLBACK_TOML;
    }

    private void ensureConfig() {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            return;
        }

        try {
            PathUtils.createParentDirectories(configPath);
            Files.writeString(configPath, loadDefaultTemplateContent(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to initialize build config", e);
        }
    }

    private String detectTemplate() {
        Path root = projectService.getCurrentProjectRoot();
        if (Files.exists(root.resolve("pom.xml"))) {
            return "maven";
        }
        if (Files.exists(root.resolve("build.gradle"))
                || Files.exists(root.resolve("build.gradle.kts"))) {
            return "gradle";
        }
        if (Files.exists(root.resolve("package.json"))) {
            return "npm";
        }
        if (Files.exists(root.resolve("requirements.txt"))
                || Files.exists(root.resolve("pyproject.toml"))) {
            return "python";
        }
        if (Files.exists(root.resolve("go.mod")) || Files.exists(root.resolve("main.go"))) {
            return "go";
        }
        if (Files.exists(root.resolve("CMakeLists.txt"))) {
            return "cmake";
        }
        return "default";
    }

    private Map<String, Object> loadConfig() throws IOException {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                return tomlMapper.readValue(configPath.toFile(), Map.class);
            } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException e) {
                log.warn("Build config file not found at {}, falling back to template", configPath);
            }
        }
        return tomlMapper.readValue(loadDefaultTemplateContent(), Map.class);
    }

    private String getCommand(Map<String, Object> config, String section, String key) {
        Object sectionObj = config.get(section);
        if (sectionObj instanceof Map<?, ?> sectionMap) {
            Object cmd = sectionMap.get(key);
            return cmd instanceof String s ? s : null;
        }
        return null;
    }

    private String getParseRegex(Map<String, Object> config, String key) {
        Map<String, Object> parse = (Map<String, Object>) config.get("parse");
        return parse != null ? (String) parse.get(key) : null;
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

    ContainerConfig getContainerConfig(Map<String, Object> config) {
        return buildContainerService.parseContainerConfig(config);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private BuildResult executeInContainer(
            String command,
            ContainerConfig containerConfig,
            Map<String, String> env,
            String errorRegex,
            String testFailureRegex) {
        if (!buildContainerService.isDockerAvailable()) {
            stopActiveContainer();
            String msg =
                    "Docker was not detected or the Docker daemon is not running. "
                            + "Cannot run isolated build in Testcontainers. Please start Docker or set [container] enabled = false in build.toml.";
            return new BuildResult(false, -1, msg, List.of(msg), List.of(), msg);
        }

        log.info("Executing build command in container ({}): {}", containerConfig.image(), command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput;

        try {
            ContainerExecResult result =
                    buildContainerService.executeCommand(
                            projectService.getCurrentProjectRoot(), containerConfig, env, command);
            fullOutput = result.combinedOutput();
            exitCode = result.exitCode();
        } catch (CancellationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Container build command interrupted");
        } catch (Exception e) {
            log.error("Container execution failed", e);
            stopActiveContainer();
            String errorMsg =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            errors.add("Container execution failed: " + errorMsg);
            fullOutput = errorMsg;
        }

        return buildResultFromExecution(exitCode, fullOutput, errors, errorRegex, testFailureRegex);
    }

    private BuildResult execute(
            String command, Map<String, String> env, String errorRegex, String testFailureRegex) {
        log.info("Executing build command: {}", command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput = "";

        try {
            List<String> shellCommand =
                    SystemUtils.IS_OS_WINDOWS
                            ? List.of("cmd.exe", "/c", command)
                            : List.of("sh", "-c", command);

            ProcessResult result =
                    ProcessRunner.run(
                            shellCommand,
                            projectService.getCurrentProjectRoot(),
                            env,
                            BUILD_TIMEOUT,
                            true);

            fullOutput = result.output();
            exitCode = result.exitCode();
            if (result.timedOut()) {
                errors.add("Command timed out after 10 minutes");
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Process execution failed", e);
            errors.add(ExceptionUtils.getStackTrace(e));
        }

        return buildResultFromExecution(exitCode, fullOutput, errors, errorRegex, testFailureRegex);
    }

    private BuildResult buildResultFromExecution(
            int exitCode,
            String fullOutput,
            List<String> errors,
            String errorRegex,
            String testFailureRegex) {
        errors.addAll(extractRegexMatches(fullOutput, errorRegex, "errors"));
        List<String> failedTests =
                new ArrayList<>(extractRegexMatches(fullOutput, testFailureRegex, "test failures"));
        boolean success = exitCode == 0 && errors.isEmpty() && failedTests.isEmpty();
        return new BuildResult(success, exitCode, fullOutput, errors, failedTests, fullOutput);
    }

    private List<String> extractRegexMatches(String output, String regex, String patternType) {
        List<String> matches = new ArrayList<>();
        if (regex != null && !regex.isBlank()) {
            try {
                Pattern p = Pattern.compile(regex);
                Matcher m = p.matcher(output);
                while (m.find()) {
                    matches.add(m.group(m.groupCount() > 0 ? 1 : 0));
                }
            } catch (Exception e) {
                log.error("Failed to parse {} with regex: {}", patternType, regex, e);
            }
        }
        return matches;
    }

    public String getConfigContent() throws IOException {
        if (!projectService.hasActiveProject()) {
            return "";
        }
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            ensureConfig();
        }
        if (Files.exists(configPath)) {
            try {
                return Files.readString(configPath, StandardCharsets.UTF_8);
            } catch (java.nio.file.NoSuchFileException e) {
                log.warn("Build config file not found at {}, falling back to template", configPath);
            }
        }
        return loadDefaultTemplateContent();
    }

    public void saveConfigContent(String content) throws IOException {
        if (!projectService.hasActiveProject()) {
            return;
        }
        stopActiveContainer();
        Path configPath = getConfigPath();
        PathUtils.createParentDirectories(configPath);
        Files.writeString(configPath, content, StandardCharsets.UTF_8);
    }
}
