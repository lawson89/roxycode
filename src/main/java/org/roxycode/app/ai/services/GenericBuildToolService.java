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
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.text.StringSubstitutor;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.SandboxConfig;
import org.roxycode.app.ai.services.BuildContainerService.SandboxMode;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

@AgentService(value = "buildToolService", phases = {"CODE", "EXPLORE", "PLAN", "REVIEW"})
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class GenericBuildToolService {

    private static final Logger log = LoggerFactory.getLogger(GenericBuildToolService.class);
    private static final String CONFIG_FILE = "build.toml";
    static final String BUILD_LOG_REL_PATH = "tmp/build.log";
    static final int MAX_LOG_TAIL_BYTES = 4096;
    static final String LOG_TRUNCATED_PREFIX = "... [Log truncated. Full log saved to .roxycode/tmp/build.log]"
            + (char) 10;
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);
    private static final String DEFAULT_FALLBACK_TOML = """
            [commands]
            install = ""
            clean = ""
            compile = ""
            build = ""
            test_all = ""
            test_single = ""
            lint = ""
            format = ""

            [sandbox]
            # Isolation mode: "none" | "native" (Linux bwrap / macOS sandbox-exec) | "docker"
            mode = "none"
            # Additional host paths mounted read-write in "native" mode (supports ~/ expansion)
            writable_paths = []
            # Docker settings (used when mode = "docker")
            image = "ubuntu:24.04"
            workdir = "/workspace"
            """;

    public record BuildTemplateInfo(String id, String displayName, String description, List<String> markerFiles) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final List<BuildTemplateInfo> AVAILABLE_TEMPLATES = List.of(
            new BuildTemplateInfo("maven", "Maven", "Java project with pom.xml", List.of("pom.xml")),
            new BuildTemplateInfo("gradle", "Gradle", "JVM project with build.gradle or build.gradle.kts",
                    List.of("build.gradle", "build.gradle.kts")),
            new BuildTemplateInfo("npm", "npm / Node.js", "JavaScript/TypeScript project with package.json",
                    List.of("package.json")),
            new BuildTemplateInfo("python", "Python", "Python project with requirements.txt or pyproject.toml",
                    List.of("requirements.txt", "pyproject.toml")),
            new BuildTemplateInfo("go", "Go", "Go module with go.mod or main.go", List.of("go.mod", "main.go")),
            new BuildTemplateInfo("rust", "Rust", "Cargo Rust project with Cargo.toml", List.of("Cargo.toml")),
            new BuildTemplateInfo("cmake", "CMake", "C/C++ project with CMakeLists.txt", List.of("CMakeLists.txt")),
            new BuildTemplateInfo("default", "No-Op / Manual Setup", "Blank commands fallback template", List.of()));

    public List<BuildTemplateInfo> getAvailableTemplates() {
        return AVAILABLE_TEMPLATES;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandsSection(String install, String clean, String compile, String build,
            @JsonProperty("test_all") String testAll, @JsonProperty("test_single") String testSingle, String lint,
            String format) {
        public static final CommandsSection EMPTY = new CommandsSection(null, null, null, null, null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseSection(@JsonProperty("error_regex") String errorRegex,
            @JsonProperty("test_failure_regex") String testFailureRegex) {
        public static final ParseSection EMPTY = new ParseSection(null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildConfig(CommandsSection commands, Map<String, String> env, ParseSection parse,
            SandboxConfig sandbox) {
        public BuildConfig {
            commands = Objects.requireNonNullElse(commands, CommandsSection.EMPTY);
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
            parse = Objects.requireNonNullElse(parse, ParseSection.EMPTY);
            sandbox = Objects.requireNonNullElse(sandbox, SandboxConfig.DEFAULT);
        }
    }

    private final ProjectService projectService;
    private final TomlMapper tomlMapper;
    private final BuildContainerService buildContainerService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlanService planService;

    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper,
            BuildContainerService buildContainerService) {
        this(projectService, tomlMapper, buildContainerService, null, null);
    }

    @Autowired
    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper,
            BuildContainerService buildContainerService, @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable PlanService planService) {
        this.projectService = projectService;
        this.tomlMapper = tomlMapper;
        this.buildContainerService = buildContainerService;
        this.eventPublisher = eventPublisher;
        this.planService = planService;
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

    static String formatNoOpOutput(String commandLabel) {
        return "Command '" + commandLabel + "' is not configured (no-op).";
    }

    @AgentDoc(value = "Installs or syncs project dependencies using the configured install command.", phases = {
            "CODE"}, examples = "buildToolService.install()")
    public BuildResult install() {
        requireCodePhase("install");
        return notifyIfMutated("commands.install",
                runCommand("commands.install", config -> config.commands().install()));
    }

    @AgentDoc(value = "Cleans the project using the configured clean command", examples = "buildToolService.clean()")
    public BuildResult clean() {
        return runCommand("commands.clean", config -> config.commands().clean());
    }

    @AgentDoc(value = "Compiles the project using the configured compile command", examples = "buildToolService.compile()")
    public BuildResult compile() {
        BuildResult result = runCommand("commands.compile", config -> config.commands().compile());
        if (planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, result.success());
        }
        return result;
    }

    @AgentDoc(value = "Builds or packages the project using the configured build command (falls back to compile if build is not configured).", examples = "buildToolService.build()")
    public BuildResult build() {
        return runCommand("commands.build",
                c -> StringUtils.firstNonBlank(c.commands().build(), c.commands().compile()));
    }

    @AgentDoc(value = "Checks if a generic build configuration exists (build.toml)", examples = "buildToolService.detect()")
    public boolean detect() {
        return projectService.hasActiveProject() && Files.exists(getConfigPath());
    }

    @AgentDoc(value = "Runs all tests using the configured test_all command", examples = "buildToolService.runTests()")
    public BuildResult runTests() {
        BuildResult result = runCommand("commands.test_all", config -> config.commands().testAll());
        if (planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, result.success());
            if (result.success()) {
                planService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
            }
        }
        return result;
    }

    @AgentDoc(value = "Runs a single test using the configured test_single command. {testName} is replaced with the argument.", examples = "buildToolService.runSingleTest('com.example.TestClass')")
    public BuildResult runSingleTest(
            @AgentDoc("The name of the test class or method (e.g., MyTest or MyTest#myMethod)") String testName) {
        return runCommand("commands.test_single", config -> config.commands().testSingle(),
                Map.of("testName", StringUtils.defaultString(testName)));
    }

    @AgentDoc(value = "Runs the project linter or static analysis checks using the configured lint command.", examples = "buildToolService.lint()")
    public BuildResult lint() {
        return runCommand("commands.lint", config -> config.commands().lint());
    }

    @AgentDoc(value = "Formats the project source code using the configured format command.", phases = {
            "CODE"}, examples = "buildToolService.format()")
    public BuildResult format() {
        requireCodePhase("format");
        return notifyIfMutated("commands.format", runCommand("commands.format", config -> config.commands().format()));
    }

    private BuildResult notifyIfMutated(String label, BuildResult result) {
        if (result.success() && !formatNoOpOutput(label).equals(result.output()) && eventPublisher != null) {
            eventPublisher.publishEvent(new FileChangedEvent(""));
        }
        return result;
    }

    private BuildResult runCommand(String commandLabel, Function<BuildConfig, String> commandExtractor) {
        return runCommand(commandLabel, commandExtractor, Map.of());
    }

    @AgentDoc(value = "Compiles the project, runs all tests, and runs lint checks sequentially. Stops on the first failure. Use this for verification.", examples = "buildToolService.buildAndTest()")
    public BuildResult buildAndTest() {
        BuildResult compileResult = compile();
        if (!compileResult.success()) {
            return compileResult;
        }
        BuildResult testResult = runTests();
        if (!testResult.success()) {
            return new BuildResult(false, testResult.exitCode(), """
                    COMPILE OUTPUT:
                    %s

                    TEST OUTPUT:
                    %s""".formatted(compileResult.output(), testResult.output()), testResult.errors(),
                    testResult.failedTests());
        }
        BuildResult lintResult = lint();
        return new BuildResult(lintResult.success(), lintResult.exitCode(), """
                COMPILE OUTPUT:
                %s

                TEST OUTPUT:
                %s

                LINT OUTPUT:
                %s""".formatted(compileResult.output(), testResult.output(), lintResult.output()), lintResult.errors(),
                lintResult.failedTests());
    }

    private void requireCodePhase(String methodName) {
        if (planService == null) {
            return;
        }
        WorkflowPhase current = planService.getCurrentPhase();
        if (current != WorkflowPhase.CODE) {
            throw new IllegalStateException(
                    "Method '" + methodName + "' is only allowed in phase(s) [CODE]. Current phase: " + current);
        }
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
                return new BuildResult(true, 0, formatNoOpOutput(commandLabel), List.of(), List.of());
            }
            if (!placeholders.isEmpty()) {
                command = new StringSubstitutor(placeholders, "{", "}").replace(command);
            }
            String errorRegex = config.parse().errorRegex();
            String testFailureRegex = config.parse().testFailureRegex();
            Map<String, String> env = config.env();
            SandboxConfig sandboxConfig = config.sandbox();
            SandboxMode mode = sandboxConfig.resolvedMode();
            Path logFile = prepareBuildLogFile();
            if (mode == SandboxMode.DOCKER) {
                if (!buildContainerService.isDockerAvailable()) {
                    stopActiveContainer();
                    String msg = "Error executing " + commandLabel + ": Docker is not available, but [sandbox] mode = "
                            + (char) 34 + "docker" + (char) 34 + " is configured in build.toml.";
                    return new BuildResult(false, -1, msg, List.of(msg), List.of());
                }
                return executeInContainer(command, sandboxConfig, env, logFile, errorRegex, testFailureRegex);
            }
            if (mode == SandboxMode.NATIVE) {
                stopActiveContainer();
                if (!buildContainerService.isNativeSandboxAvailable()) {
                    String msg = "Error executing " + commandLabel
                            + ": Native OS sandbox is not available on this system, but [sandbox] mode = " + (char) 34
                            + "native" + (char) 34 + " is configured in build.toml.";
                    return new BuildResult(false, -1, msg, List.of(msg), List.of());
                }
                List<String> sandboxCmd = buildContainerService
                        .buildNativeSandboxCommand(projectService.getCurrentProjectRoot(), sandboxConfig, command);
                return executeCommandList(sandboxCmd, env, logFile, errorRegex, testFailureRegex);
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

    public final String loadTemplateContent(String templateId) {
        String id = StringUtils.defaultIfBlank(templateId, "default");
        try {
            return PathUtils.readClasspathResourceUtf8("templates/build/" + id + ".toml",
                    GenericBuildToolService.class);
        } catch (IOException e) {
            log.warn("Failed to read build template: {}", id, e);
            return DEFAULT_FALLBACK_TOML;
        }
    }

    private String loadDefaultTemplateContent() {
        return loadTemplateContent(detectTemplate());
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

    public final String detectTemplate() {
        if (!projectService.hasActiveProject()) {
            return "default";
        }
        Path root = projectService.getCurrentProjectRoot();
        for (BuildTemplateInfo info : AVAILABLE_TEMPLATES) {
            if (info.markerFiles().stream().anyMatch(m -> Files.exists(root.resolve(m)))) {
                return info.id();
            }
        }
        return "default";
    }

    BuildConfig loadConfig() throws IOException {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                return parseConfig(Files.readString(configPath, StandardCharsets.UTF_8));
            } catch (NoSuchFileException | FileNotFoundException e) {
                log.warn("Build config file not found at {}, falling back to template", configPath);
            }
        }
        return parseConfig(loadDefaultTemplateContent());
    }

    BuildConfig parseConfig(String rawToml) throws IOException {
        String content = StringUtils.defaultString(rawToml);
        return tomlMapper.readValue(sanitizeTomlEscapes(content), BuildConfig.class);
    }

    static String sanitizeTomlEscapes(String toml) {
        if (toml == null || toml.indexOf((char) 92) < 0) {
            return StringUtils.defaultString(toml);
        }
        StringBuilder sb = new StringBuilder(toml.length() + 16);
        int len = toml.length();
        boolean inBasic = false;
        boolean inMultilineBasic = false;
        boolean inLiteral = false;
        boolean inMultilineLiteral = false;
        boolean inComment = false;
        int i = 0;
        while (i < len) {
            char c = toml.charAt(i);
            if (inComment) {
                sb.append(c);
                if (c == (char) 10 || c == (char) 13) {
                    inComment = false;
                }
                i++;
            } else if (inLiteral) {
                sb.append(c);
                if (c == (char) 39 || c == (char) 10 || c == (char) 13) {
                    inLiteral = false;
                }
                i++;
            } else if (inMultilineLiteral) {
                if (startsWithTripleQuote(toml, i, (char) 39)) {
                    sb.append("'''");
                    inMultilineLiteral = false;
                    i += 3;
                } else {
                    sb.append(c);
                    i++;
                }
            } else if (inBasic || inMultilineBasic) {
                if (inMultilineBasic && startsWithTripleQuote(toml, i, (char) 34)) {
                    sb.append((char) 34).append((char) 34).append((char) 34);
                    inMultilineBasic = false;
                    i += 3;
                } else if (inBasic && (c == (char) 34 || c == (char) 10 || c == (char) 13)) {
                    sb.append(c);
                    inBasic = false;
                    i++;
                } else if (c == (char) 92 && i + 1 < len) {
                    char next = toml.charAt(i + 1);
                    if (isValidTomlEscape(toml, i + 1, inMultilineBasic)) {
                        sb.append((char) 92).append(next);
                        i += 2;
                    } else {
                        sb.append((char) 92).append((char) 92);
                        i++;
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            } else if (c == '#') {
                inComment = true;
                sb.append(c);
                i++;
            } else if (startsWithTripleQuote(toml, i, (char) 34)) {
                inMultilineBasic = true;
                sb.append((char) 34).append((char) 34).append((char) 34);
                i += 3;
            } else if (startsWithTripleQuote(toml, i, (char) 39)) {
                inMultilineLiteral = true;
                sb.append("'''");
                i += 3;
            } else if (c == (char) 34) {
                inBasic = true;
                sb.append(c);
                i++;
            } else if (c == (char) 39) {
                inLiteral = true;
                sb.append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean startsWithTripleQuote(String text, int index, char quoteChar) {
        return index + 2 < text.length() && text.charAt(index) == quoteChar && text.charAt(index + 1) == quoteChar
                && text.charAt(index + 2) == quoteChar;
    }

    private static boolean isValidTomlEscape(String text, int escapeCharIndex, boolean multilineBasic) {
        char next = text.charAt(escapeCharIndex);
        if (next == 'b' || next == 't' || next == 'n' || next == 'f' || next == 'r' || next == (char) 34
                || next == (char) 92) {
            return true;
        }
        if (next == 'u') {
            return hasHexDigits(text, escapeCharIndex + 1, 4);
        }
        if (next == 'U') {
            return hasHexDigits(text, escapeCharIndex + 1, 8);
        }
        if (multilineBasic && (next == ' ' || next == (char) 9 || next == (char) 10 || next == (char) 13)) {
            return true;
        }
        return false;
    }

    private static boolean hasHexDigits(String text, int start, int count) {
        if (start + count > text.length()) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            char ch = text.charAt(start + i);
            boolean isHex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private BuildResult executeInContainer(String command, SandboxConfig sandboxConfig, Map<String, String> env,
            Path logFile, String errorRegex, String testFailureRegex) {
        log.info("Executing build command in container ({}): {}", sandboxConfig.image(), command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput;

        try {
            ContainerExecResult result = buildContainerService.executeCommand(projectService.getCurrentProjectRoot(),
                    sandboxConfig, env, command);
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
            String errorMsg = org.roxycode.app.util.ExceptionUtils.getMessageOrSimpleName(e);
            errors.add("Container execution failed: " + errorMsg);
            fullOutput = errorMsg;
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, errorRegex, testFailureRegex);
        }
    }

    private BuildResult execute(String command, Map<String, String> env, Path logFile, String errorRegex,
            String testFailureRegex) {
        log.info("Executing build command: {}", command);
        List<String> shellCommand;
        if (SystemUtils.IS_OS_WINDOWS) {
            shellCommand = List.of("cmd.exe", "/c", command);
        } else {
            shellCommand = List.of("sh", "-c", command);
        }
        return executeCommandList(shellCommand, env, logFile, errorRegex, testFailureRegex);
    }

    private BuildResult executeCommandList(List<String> commandList, Map<String, String> env, Path logFile,
            String errorRegex, String testFailureRegex) {
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput = "";

        try {
            ProcessResult result;
            try (OutputStream fileOut = Files.newOutputStream(logFile)) {
                result = ProcessRunner.run(commandList, projectService.getCurrentProjectRoot(), env, BUILD_TIMEOUT,
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
        errors.addAll(extractWithRipGrep(errorRegex, logFile));
        List<String> failedTests = new ArrayList<>(extractWithRipGrep(testFailureRegex, logFile));
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

    List<String> extractWithRipGrep(String regex, Path logFile) {
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
        String normalizedContent = StringUtils.defaultString(content);
        parseConfig(normalizedContent);
        stopActiveContainer();
        Path configPath = getConfigPath();
        PathUtils.createParentDirectories(configPath);
        Files.writeString(configPath, normalizedContent, StandardCharsets.UTF_8);
    }
}
