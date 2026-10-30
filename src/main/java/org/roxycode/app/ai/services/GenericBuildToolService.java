package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.swing.SwingUtilities;
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
import org.springframework.core.io.ClassPathResource;

@AgentService(value = "buildToolService", phases = {"CODE", "EXPLORE", "PLAN", "REVIEW"})
public class GenericBuildToolService {

    private static final Logger log = LoggerFactory.getLogger(GenericBuildToolService.class);
    private static final String CONFIG_FILE = "build.toml";
    static final String BUILD_LOG_REL_PATH = "tmp/build.log";
    static final int MAX_LOG_TAIL_BYTES = 4096;
    static final String LOG_TRUNCATED_PREFIX = "... [Log truncated. Full log saved to .roxycode/tmp/build.log]"
            + (char) 10;
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);
    private static final String DEFAULT_FALLBACK_TOML = """
            [build]
            compile = 'echo No build command'""";

    private record TemplateRule(String template, List<String> markerFiles) {
    }

    private static final List<TemplateRule> TEMPLATE_RULES = List.of(new TemplateRule("maven", List.of("pom.xml")),
            new TemplateRule("gradle", List.of("build.gradle", "build.gradle.kts")),
            new TemplateRule("npm", List.of("package.json")),
            new TemplateRule("python", List.of("requirements.txt", "pyproject.toml")),
            new TemplateRule("go", List.of("go.mod", "main.go")), new TemplateRule("cmake", List.of("CMakeLists.txt")));

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildSection(String compile, String clean) {
        public static final BuildSection EMPTY = new BuildSection(null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestSection(@JsonProperty("run_all") String runAll, @JsonProperty("run_single") String runSingle) {
        public static final TestSection EMPTY = new TestSection(null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseSection(@JsonProperty("error_regex") String errorRegex,
            @JsonProperty("test_failure_regex") String testFailureRegex) {
        public static final ParseSection EMPTY = new ParseSection(null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildConfig(BuildSection build, TestSection test, Map<String, String> env, ParseSection parse,
            ContainerConfig container) {
        public BuildConfig {
            if (build == null) {
                build = BuildSection.EMPTY;
            }
            if (test == null) {
                test = TestSection.EMPTY;
            }
            if (env == null || env.isEmpty()) {
                env = Map.of();
            } else {
                Map<String, String> sanitized = new HashMap<>();
                env.forEach((k, v) -> {
                    if (k != null && v != null) {
                        sanitized.put(k, v);
                    }
                });
                env = Collections.unmodifiableMap(sanitized);
            }
            if (parse == null) {
                parse = ParseSection.EMPTY;
            }
            if (container == null) {
                container = ContainerConfig.DEFAULT;
            }
        }
    }

    private final ProjectService projectService;
    private final TomlMapper tomlMapper;
    private final BuildContainerService buildContainerService;

    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper,
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
        if (SwingUtilities.isEventDispatchThread()) {
            CompletableFuture.runAsync(this::performProjectChanged);
        } else {
            performProjectChanged();
        }
    }

    private void performProjectChanged() {
        stopActiveContainer();
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    public void stopActiveContainer() {
        buildContainerService.stopActiveContainer();
    }

    @AgentDoc(value = "Cleans the project using the configured clean command", examples = "buildToolService.clean()")
    public BuildResult clean() {
        return runCommand("build.clean", config -> config.build().clean());
    }

    @AgentDoc(value = "Compiles the project using the configured compile command", examples = "buildToolService.compile()")
    public BuildResult compile() {
        return runCommand("build.compile", config -> config.build().compile());
    }

    @AgentDoc(value = "Checks if a generic build configuration exists (build.toml)", examples = "buildToolService.detect()")
    public boolean detect() {
        return projectService.hasActiveProject() && Files.exists(getConfigPath());
    }

    @AgentDoc(value = "Runs all tests using the configured test command", examples = "buildToolService.runTests()")
    public BuildResult runTests() {
        return runCommand("test.run_all", config -> config.test().runAll());
    }

    @AgentDoc(value = "Runs a single test using the configured run_single command. {testName} is replaced with the argument.", examples = "buildToolService.runSingleTest('com.example.TestClass')")
    public BuildResult runSingleTest(
            @AgentDoc("The name of the test class or method (e.g., MyTest or MyTest#myMethod)") String testName) {
        return runCommand("test.run_single", config -> config.test().runSingle(), Map.of("testName", testName));
    }

    private BuildResult runCommand(String commandLabel, Function<BuildConfig, String> commandExtractor) {
        return runCommand(commandLabel, commandExtractor, Map.of());
    }

    @AgentDoc(value = "Compiles the project and, if successful, runs all tests. Use this for the Verification phase.", examples = "buildToolService.buildAndTest()")
    public BuildResult buildAndTest() {
        BuildResult compileResult = compile();
        if (!compileResult.success()) {
            return compileResult;
        }
        BuildResult testResult = runTests();
        return new BuildResult(testResult.success(), testResult.exitCode(), """
                COMPILE OUTPUT:
                %s

                TEST OUTPUT:
                %s""".formatted(compileResult.output(), testResult.output()), testResult.errors(),
                testResult.failedTests());
    }

    private BuildResult runCommand(String commandLabel, Function<BuildConfig, String> commandExtractor,
            Map<String, String> placeholders) {
        if (!projectService.hasActiveProject()) {
            return new BuildResult(false, -1, "", List.of("No active project"), List.of());
        }
        ensureConfig();

        try {
            BuildConfig config = loadConfig();
            String command = commandExtractor.apply(config);
            if (command == null || command.isBlank()) {
                return new BuildResult(false, -1, "", List.of("Command not configured: " + commandLabel), List.of());
            }
            if (!placeholders.isEmpty()) {
                command = new StringSubstitutor(placeholders, "{", "}").replace(command);
            }
            String errorRegex = config.parse().errorRegex();
            String testFailureRegex = config.parse().testFailureRegex();
            Map<String, String> env = config.env();
            ContainerConfig containerConfig = config.container();
            Path logFile = prepareBuildLogFile();
            if (containerConfig.enabled()) {
                return executeInContainer(command, containerConfig, env, logFile, errorRegex, testFailureRegex);
            }
            stopActiveContainer();
            return execute(command, env, logFile, errorRegex, testFailureRegex);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to run build command: {}", commandLabel, e);
            return new BuildResult(false, -1, "", List.of(e.getMessage()), List.of());
        }
    }

    private Path getConfigPath() {
        return projectService.resolveProjectDataPath(CONFIG_FILE);
    }

    private Path prepareBuildLogFile() {
        Path logFile = projectService.resolveProjectDataPath(BUILD_LOG_REL_PATH);
        try {
            PathUtils.createParentDirectories(logFile);
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            log.warn("Failed to prepare build log file {}: {}", logFile, e.getMessage());
        }
        return logFile;
    }

    @SuppressWarnings("PMD.UseProperClassLoader")
    private String loadDefaultTemplateContent() {
        String templateName = detectTemplate();
        try {
            return new ClassPathResource("templates/build/" + templateName + ".toml",
                    GenericBuildToolService.class.getClassLoader()).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read build template: {}", templateName, e);
            return DEFAULT_FALLBACK_TOML;
        }
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
        for (TemplateRule rule : TEMPLATE_RULES) {
            if (rule.markerFiles().stream().anyMatch(m -> Files.exists(root.resolve(m)))) {
                return rule.template();
            }
        }
        return "default";
    }

    BuildConfig loadConfig() throws IOException {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                return tomlMapper.readValue(configPath.toFile(), BuildConfig.class);
            } catch (NoSuchFileException | FileNotFoundException e) {
                log.warn("Build config file not found at {}, falling back to template", configPath);
            }
        }
        return tomlMapper.readValue(loadDefaultTemplateContent(), BuildConfig.class);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private BuildResult executeInContainer(String command, ContainerConfig containerConfig, Map<String, String> env,
            Path logFile, String errorRegex, String testFailureRegex) {
        if (!buildContainerService.isDockerAvailable()) {
            stopActiveContainer();
            String msg = "Docker was not detected or the Docker daemon is not running. "
                    + "Cannot run isolated build in Testcontainers. Please start Docker or set [container] enabled = false in build.toml.";
            return new BuildResult(false, -1, msg, List.of(msg), List.of());
        }

        log.info("Executing build command in container ({}): {}", containerConfig.image(), command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput;

        try {
            ContainerExecResult result = buildContainerService.executeCommand(projectService.getCurrentProjectRoot(),
                    containerConfig, env, command);
            fullOutput = result.combinedOutput();
            exitCode = result.exitCode();
            Files.writeString(logFile, fullOutput, StandardCharsets.UTF_8);
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, errorRegex, testFailureRegex);
        } catch (CancellationException e) {
            stopActiveContainer();
            throw e;
        } catch (InterruptedException e) {
            stopActiveContainer();
            Thread.currentThread().interrupt();
            throw new CancellationException("Container build command interrupted");
        } catch (Exception e) {
            log.error("Container execution failed", e);
            stopActiveContainer();
            String errorMsg = e.getClass().getSimpleName();
            if (e.getMessage() != null) {
                errorMsg = e.getMessage();
            }
            errors.add("Container execution failed: " + errorMsg);
            fullOutput = errorMsg;
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, errorRegex, testFailureRegex);
        }
    }

    private BuildResult execute(String command, Map<String, String> env, Path logFile, String errorRegex,
            String testFailureRegex) {
        log.info("Executing build command: {}", command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput = "";

        try {
            List<String> shellCommand;
            if (SystemUtils.IS_OS_WINDOWS) {
                shellCommand = List.of("cmd.exe", "/c", command);
            } else {
                shellCommand = List.of("sh", "-c", command);
            }

            ProcessResult result;
            try (OutputStream fileOut = Files.newOutputStream(logFile)) {
                result = ProcessRunner.run(shellCommand, projectService.getCurrentProjectRoot(), env, BUILD_TIMEOUT,
                        true, fileOut);
            }
            fullOutput = result.output();
            exitCode = result.exitCode();
            if (result.timedOut()) {
                errors.add("Command timed out after 10 minutes");
            } else if (exitCode < 0 && !result.errorOutput().isBlank()) {
                errors.add(result.errorOutput());
            }
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, errorRegex, testFailureRegex);
        } catch (IOException e) {
            log.error("Process execution failed", e);
            errors.add(ExceptionUtils.getStackTrace(e));
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, errorRegex, testFailureRegex);
        }
    }

    private BuildResult buildResultFromExecution(int exitCode, String fullOutput, Path logFile, List<String> errors,
            String errorRegex, String testFailureRegex) {
        errors.addAll(extractWithRipGrep(errorRegex, logFile, "errors"));
        List<String> failedTests = new ArrayList<>(extractWithRipGrep(testFailureRegex, logFile, "test failures"));
        boolean success = exitCode == 0 && errors.isEmpty() && failedTests.isEmpty();
        String tailOutput = getLogTail(fullOutput);
        return new BuildResult(success, exitCode, tailOutput, errors, failedTests);
    }

    static String getLogTail(String fullOutput) {
        if (fullOutput == null || fullOutput.isEmpty()) {
            return "";
        }
        if (fullOutput.length() <= MAX_LOG_TAIL_BYTES) {
            return fullOutput;
        }
        return LOG_TRUNCATED_PREFIX + fullOutput.substring(fullOutput.length() - MAX_LOG_TAIL_BYTES);
    }

    List<String> extractWithRipGrep(String regex, Path logFile, String patternType) {
        if (regex == null || regex.isBlank() || logFile == null || !Files.exists(logFile)) {
            return List.of();
        }
        List<String> command = List.of("rg", "--color=never", "-N", "-e", regex, logFile.toString());
        return GrepService.runRipGrepLines(command, null, false).stream().map(String::trim)
                .filter(line -> !line.isEmpty()).toList();
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
            } catch (NoSuchFileException e) {
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
