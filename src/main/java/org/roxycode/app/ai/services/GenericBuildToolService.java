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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * Service for interacting with arbitrary build tools via .roxycode/build.toml. Provides methods for
 * compilation, testing, and cleaning.
 */
@AgentService(
        value = "buildToolService",
        phases = {"CODE", "EXPLORE", "PLAN", "REVIEW"})
public class GenericBuildToolService {

    private static final Logger log = LoggerFactory.getLogger(GenericBuildToolService.class);
    private static final String CONFIG_PATH = ".roxycode/build.toml";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

    private final ProjectService projectService;
    private final TomlMapper tomlMapper;

    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper) {
        this.projectService = projectService;
        this.tomlMapper = tomlMapper;
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    @EventListener
    public void handleProjectChanged(ProjectChangedEvent event) {
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    @AgentDoc(
            value = "Cleans the project using the configured clean command",
            examples = "buildToolService.clean()")
    public BuildResult clean() {
        return runCommand("clean");
    }

    @AgentDoc(
            value = "Compiles the project using the configured compile command",
            examples = "buildToolService.compile()")
    public BuildResult compile() {
        return runCommand("compile");
    }

    @AgentDoc(
            value = "Checks if a generic build configuration exists (.roxycode/build.toml)",
            examples = "buildToolService.detect()")
    public boolean detect() {
        if (!projectService.hasActiveProject()) {
            return false;
        }
        return Files.exists(projectService.getCurrentProjectRoot().resolve(CONFIG_PATH));
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
        return runCommand("run_all");
    }

    @AgentDoc(
            value =
                    "Runs a single test using the configured run_single command. {testName} is replaced with the argument.",
            examples = "buildToolService.runSingleTest('com.example.TestClass')")
    public BuildResult runSingleTest(String testName) {
        return runCommand("run_single", Map.of("testName", testName));
    }

    private BuildResult runCommand(String commandKey) {
        return runCommand(commandKey, Map.of());
    }

    @AgentDoc(
            value =
                    "Compiles the project and, if successful, runs all tests. Use this for the Verification phase.",
            examples = "buildToolService.buildAndTest()")
    public BuildResult buildAndTest() {
        BuildResult compileResult = compile();
        if (!compileResult.isSuccess()) {
            return compileResult; // Fast fail if compilation breaks
        }
        BuildResult testResult = runTests();
        // Combine outputs for the LLM
        return new BuildResult(
                testResult.isSuccess(),
                testResult.getExitCode(),
                "COMPILE OUTPUT:\n"
                        + compileResult.getOutput()
                        + "\n\nTEST OUTPUT:\n"
                        + testResult.getOutput(),
                testResult.getErrors(),
                testResult.getFailedTests(),
                testResult.getLog());
    }

    private BuildResult runCommand(String commandKey, Map<String, String> placeholders) {
        if (!projectService.hasActiveProject()) {
            return new BuildResult(
                    false, -1, "", List.of("No active project"), List.of(), "No active project");
        }

        ensureConfig();

        try {
            Map<String, Object> config = loadConfig();
            String command = getCommand(config, commandKey);
            if (command == null || command.isBlank()) {
                return new BuildResult(
                        false,
                        -1,
                        "",
                        List.of("Command not configured: " + commandKey),
                        List.of(),
                        "Command not configured");
            }

            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                command = command.replace("{" + entry.getKey() + "}", entry.getValue());
            }

            String errorRegex = getParseRegex(config, "error_regex");
            String testFailureRegex = getParseRegex(config, "test_failure_regex");

            Map<String, String> env = getEnv(config);
            return execute(command, env, errorRegex, testFailureRegex);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to run build command: {}", commandKey, e);
            return new BuildResult(
                    false, -1, "", List.of(e.getMessage()), List.of(), e.getMessage());
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
            try (InputStream is =
                    getClass().getResourceAsStream("/templates/build/" + templateName + ".toml")) {
                if (is != null) {
                    Files.copy(is, configPath);
                } else {
                    Files.writeString(
                            configPath,
                            "[build]\ncompile = 'echo No build command'",
                            StandardCharsets.UTF_8);
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
        Path configPath = projectService.getCurrentProjectRoot().resolve(CONFIG_PATH);
        return tomlMapper.readValue(configPath.toFile(), Map.class);
    }

    private String getCommand(Map<String, Object> config, String key) {
        if ("compile".equals(key) || "clean".equals(key)) {
            Map<String, Object> build = (Map<String, Object>) config.get("build");
            return build != null ? (String) build.get(key) : null;
        }
        if ("run_all".equals(key) || "run_single".equals(key)) {
            Map<String, Object> test = (Map<String, Object>) config.get("test");
            return test != null ? (String) test.get(key) : null;
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

    private BuildResult execute(
            String command, Map<String, String> env, String errorRegex, String testFailureRegex) {
        log.info("Executing build command: {}", command);
        List<String> errors = new ArrayList<>();
        List<String> failedTests = new ArrayList<>();
        int exitCode = -1;
        String fullOutput = "";

        try {
            List<String> shellCommand;
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                shellCommand = List.of("cmd.exe", "/c", command);
            } else {
                shellCommand = List.of("sh", "-c", command);
            }

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
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            errors.add(sw.toString());
        }

                errors.addAll(extractRegexMatches(fullOutput, errorRegex, "errors"));
        failedTests.addAll(extractRegexMatches(fullOutput, testFailureRegex, "test failures"));

        boolean success = exitCode == 0 && errors.isEmpty();
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
