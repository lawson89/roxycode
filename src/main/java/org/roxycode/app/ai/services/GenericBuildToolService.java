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
    public static final int DEFAULT_CONTEXT_LINES = 5;
    public static final int MAX_EXTRACTED_BLOCKS = 30;
    static final int MAX_JUNIT_FAILURE_LINES = 6;
    static final long JUNIT_XML_CLOCK_SKEW_MS = 2_000L;
    private static final String RG_BLOCK_SEPARATOR = "--";
    private static final String TAG_FAILURE = "failure";
    private static final String TAG_ERROR = "error";
    public static final List<String> DEFAULT_JUNIT_XML_PATHS = List.of("target/surefire-reports",
            "target/failsafe-reports", "build/test-results");
    static final String LOG_TRUNCATED_PREFIX = "... [Log truncated. Full log saved to .roxycode/tmp/build.log]\n";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);
    private static final char ESC_CHAR = '\u001b';
    private static final java.util.regex.Pattern ANSI_ESCAPE_PATTERN = java.util.regex.Pattern
            .compile(ESC_CHAR + "\\[[;\\d]*[ -/]*[@-~]");
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
            @JsonProperty("test_failure_regex") String testFailureRegex,
            @JsonProperty("context_lines") Integer contextLines,
            @JsonProperty("junit_xml_paths") List<String> junitXmlPaths) {
        public static final ParseSection EMPTY = new ParseSection(null, null, null, null);

        public int resolvedContextLines() {
            if (contextLines == null) {
                return DEFAULT_CONTEXT_LINES;
            }
            if (contextLines <= 0) {
                return 0;
            }
            return contextLines;
        }

        public List<String> resolvedJunitXmlPaths() {
            if (junitXmlPaths == null) {
                return DEFAULT_JUNIT_XML_PATHS;
            }
            List<String> sanitized = new ArrayList<>();
            for (String path : junitXmlPaths) {
                if (path != null && !path.isBlank()) {
                    sanitized.add(path.trim());
                }
            }
            return Collections.unmodifiableList(sanitized);
        }
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
        BuildResult testResult = runCommand("commands.test_all", config -> config.commands().testAll());
        if (!testResult.success()) {
            if (planService != null) {
                planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, false);
            }
            return testResult;
        }
        if (planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        }
        BuildResult lintResult = lint();
        if (!lintResult.success()) {
            return new BuildResult(false, lintResult.exitCode(), """
                    TEST OUTPUT:
                    %s

                    LINT OUTPUT:
                    %s""".formatted(testResult.output(), lintResult.output()), lintResult.errors(),
                    lintResult.failedTests());
        }
        if (planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, true);
        }
        return testResult;
    }

    @AgentDoc(value = "Runs a single test using the configured test_single command. {testName} is replaced with the argument.", examples = "buildToolService.runSingleTest('com.example.TestClass')")
    public BuildResult runSingleTest(
            @AgentDoc("The name of the test class or method (e.g., MyTest or MyTest#myMethod)") String testName) {
        return runCommand("commands.test_single", config -> config.commands().testSingle(),
                Map.of("testName", StringUtils.defaultString(testName)));
    }

    @AgentDoc(value = "Runs the project linter or static analysis checks using the configured lint command.", examples = "buildToolService.lint()")
    public BuildResult lint() {
        BuildResult result = runCommand("commands.lint", config -> config.commands().lint());
        if (!result.success() && planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, false);
        }
        return result;
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
        BuildResult testResult = runCommand("commands.test_all", config -> config.commands().testAll());
        if (!testResult.success()) {
            if (planService != null) {
                planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, false);
            }
            return new BuildResult(false, testResult.exitCode(), """
                    COMPILE OUTPUT:
                    %s

                    TEST OUTPUT:
                    %s""".formatted(compileResult.output(), testResult.output()), testResult.errors(),
                    testResult.failedTests());
        }
        BuildResult lintResult = lint();
        if (planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, lintResult.success());
        }
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
            ParseSection parseSection = config.parse();
            Map<String, String> env = config.env();
            SandboxConfig sandboxConfig = config.sandbox();
            SandboxMode mode = sandboxConfig.resolvedMode();
            Path logFile = prepareBuildLogFile();
            if (mode == SandboxMode.DOCKER) {
                if (!buildContainerService.isDockerAvailable()) {
                    stopActiveContainer();
                    String msg = "Error executing " + commandLabel
                            + ": Docker is not available, but [sandbox] mode = \"docker\" is configured in build.toml.";
                    return new BuildResult(false, -1, msg, List.of(msg), List.of());
                }
                return executeInContainer(command, sandboxConfig, env, logFile, parseSection);
            }
            if (mode == SandboxMode.NATIVE) {
                stopActiveContainer();
                if (!buildContainerService.isNativeSandboxAvailable()) {
                    String msg = "Error executing " + commandLabel
                            + ": Native OS sandbox is not available on this system, but [sandbox] mode = \"native\" is configured in build.toml.";
                    return new BuildResult(false, -1, msg, List.of(msg), List.of());
                }
                List<String> sandboxCmd = buildContainerService
                        .buildNativeSandboxCommand(projectService.getCurrentProjectRoot(), sandboxConfig, command);
                return executeCommandList(sandboxCmd, env, logFile, parseSection);
            }
            stopActiveContainer();
            return execute(command, env, logFile, parseSection);
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

    private static final char COMMENT_CHAR = '#';
    private static final char DOUBLE_QUOTE_CHAR = '"';
    private static final char SINGLE_QUOTE_CHAR = '\'';
    private static final char BACKSLASH_CHAR = '\\';
    private static final char UNICODE_ESCAPE_LOWER = 'u';
    private static final char UNICODE_ESCAPE_UPPER = 'U';
    private static final String TRIPLE_DOUBLE_QUOTE = "\"\"\"";
    private static final String DOUBLE_BACKSLASH = "\\\\";
    private static final String VALID_SIMPLE_TOML_ESCAPES = "btnfr\"\\";

    private enum TomlLexerState {
        NORMAL, COMMENT, BASIC_STRING, MULTILINE_BASIC_STRING, LITERAL_STRING, MULTILINE_LITERAL_STRING
    }

    static String sanitizeTomlEscapes(String toml) {
        if (toml == null || toml.indexOf(BACKSLASH_CHAR) < 0) {
            return StringUtils.defaultString(toml);
        }
        StringBuilder sb = new StringBuilder(toml.length() + 16);
        int len = toml.length();
        TomlLexerState state = TomlLexerState.NORMAL;
        int i = 0;
        while (i < len) {
            char c = toml.charAt(i);
            switch (state) {
                case COMMENT :
                    sb.append(c);
                    if (isLineBreak(c)) {
                        state = TomlLexerState.NORMAL;
                    }
                    i++;
                    break;
                case LITERAL_STRING :
                    sb.append(c);
                    if (c == SINGLE_QUOTE_CHAR || isLineBreak(c)) {
                        state = TomlLexerState.NORMAL;
                    }
                    i++;
                    break;
                case MULTILINE_LITERAL_STRING :
                    if (startsWithTripleQuote(toml, i, SINGLE_QUOTE_CHAR)) {
                        sb.append("'''");
                        state = TomlLexerState.NORMAL;
                        i += 3;
                    } else {
                        sb.append(c);
                        i++;
                    }
                    break;
                case BASIC_STRING :
                case MULTILINE_BASIC_STRING :
                    boolean multiline = state == TomlLexerState.MULTILINE_BASIC_STRING;
                    if (multiline && startsWithTripleQuote(toml, i, DOUBLE_QUOTE_CHAR)) {
                        sb.append(TRIPLE_DOUBLE_QUOTE);
                        state = TomlLexerState.NORMAL;
                        i += 3;
                    } else if (!multiline && (c == DOUBLE_QUOTE_CHAR || isLineBreak(c))) {
                        sb.append(c);
                        state = TomlLexerState.NORMAL;
                        i++;
                    } else if (c == BACKSLASH_CHAR && i + 1 < len) {
                        if (isValidTomlEscape(toml, i + 1, multiline)) {
                            sb.append(BACKSLASH_CHAR).append(toml.charAt(i + 1));
                            i += 2;
                        } else {
                            sb.append(DOUBLE_BACKSLASH);
                            i++;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                    break;
                default :
                    if (c == COMMENT_CHAR) {
                        state = TomlLexerState.COMMENT;
                        sb.append(c);
                        i++;
                    } else if (startsWithTripleQuote(toml, i, DOUBLE_QUOTE_CHAR)) {
                        state = TomlLexerState.MULTILINE_BASIC_STRING;
                        sb.append(TRIPLE_DOUBLE_QUOTE);
                        i += 3;
                    } else if (startsWithTripleQuote(toml, i, SINGLE_QUOTE_CHAR)) {
                        state = TomlLexerState.MULTILINE_LITERAL_STRING;
                        sb.append("'''");
                        i += 3;
                    } else if (c == DOUBLE_QUOTE_CHAR) {
                        state = TomlLexerState.BASIC_STRING;
                        sb.append(c);
                        i++;
                    } else if (c == SINGLE_QUOTE_CHAR) {
                        state = TomlLexerState.LITERAL_STRING;
                        sb.append(c);
                        i++;
                    } else {
                        sb.append(c);
                        i++;
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean startsWithTripleQuote(String text, int index, char quoteChar) {
        return index + 2 < text.length() && text.charAt(index) == quoteChar && text.charAt(index + 1) == quoteChar
                && text.charAt(index + 2) == quoteChar;
    }

    private static boolean isValidTomlEscape(String text, int escapeCharIndex, boolean multilineBasic) {
        char next = text.charAt(escapeCharIndex);
        if (VALID_SIMPLE_TOML_ESCAPES.indexOf(next) >= 0) {
            return true;
        }
        if (next == UNICODE_ESCAPE_LOWER) {
            return hasHexDigits(text, escapeCharIndex + 1, 4);
        }
        if (next == UNICODE_ESCAPE_UPPER) {
            return hasHexDigits(text, escapeCharIndex + 1, 8);
        }
        return multilineBasic && (Character.isWhitespace(next) || isLineBreak(next));
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

    static String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf(ESC_CHAR) < 0) {
            return text;
        }
        return ANSI_ESCAPE_PATTERN.matcher(text).replaceAll("");
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private BuildResult executeInContainer(String command, SandboxConfig sandboxConfig, Map<String, String> env,
            Path logFile, ParseSection parseSection) {
        log.info("Executing build command in container ({}): {}", sandboxConfig.image(), command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput;
        long startTimeMillis = System.currentTimeMillis();

        try {
            ContainerExecResult result = buildContainerService.executeCommand(projectService.getCurrentProjectRoot(),
                    sandboxConfig, env, command);
            fullOutput = stripAnsiCodes(result.combinedOutput());
            exitCode = result.exitCode();
            Files.writeString(logFile, fullOutput, StandardCharsets.UTF_8);
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis);
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
            String errorMsg = stripAnsiCodes(org.roxycode.app.util.ExceptionUtils.getMessageOrSimpleName(e));
            errors.add("Container execution failed: " + errorMsg);
            fullOutput = errorMsg;
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis);
        }
    }

    private BuildResult execute(String command, Map<String, String> env, Path logFile, ParseSection parseSection) {
        log.info("Executing build command: {}", command);
        List<String> shellCommand;
        if (SystemUtils.IS_OS_WINDOWS) {
            shellCommand = List.of("cmd.exe", "/c", command);
        } else {
            shellCommand = List.of("sh", "-c", command);
        }
        return executeCommandList(shellCommand, env, logFile, parseSection);
    }

    private BuildResult executeCommandList(List<String> commandList, Map<String, String> env, Path logFile,
            ParseSection parseSection) {
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput = "";
        long startTimeMillis = System.currentTimeMillis();

        try {
            ProcessResult result;
            try (OutputStream fileOut = Files.newOutputStream(logFile)) {
                result = ProcessRunner.run(commandList, projectService.getCurrentProjectRoot(), env, BUILD_TIMEOUT,
                        true, fileOut);
            }
            fullOutput = stripAnsiCodes(result.output());
            Files.writeString(logFile, fullOutput, StandardCharsets.UTF_8);
            exitCode = result.exitCode();
            if (result.timedOut()) {
                errors.add("Command timed out after 10 minutes");
            } else if (exitCode < 0 && !result.errorOutput().isBlank()) {
                errors.add(stripAnsiCodes(result.errorOutput()));
            }
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis);
        } catch (IOException e) {
            log.error("Process execution failed", e);
            errors.add(ExceptionUtils.getStackTrace(e));
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis);
        }
    }

    private BuildResult buildResultFromExecution(int exitCode, String fullOutput, Path logFile, List<String> errors,
            ParseSection parseSection, long startTimeMillis) {
        ParseSection effectiveParse = Objects.requireNonNullElse(parseSection, ParseSection.EMPTY);
        int contextLines = effectiveParse.resolvedContextLines();
        errors.addAll(extractWithRipGrep(effectiveParse.errorRegex(), logFile, contextLines));
        List<String> failedTests = new ArrayList<>(
                extractWithRipGrep(effectiveParse.testFailureRegex(), logFile, contextLines));
        if (exitCode != 0) {
            List<String> xmlFailures = extractJunitXmlFailures(effectiveParse.resolvedJunitXmlPaths(), startTimeMillis);
            for (String xmlFailure : xmlFailures) {
                if (failedTests.size() >= MAX_EXTRACTED_BLOCKS) {
                    break;
                }
                if (!failedTests.contains(xmlFailure)) {
                    failedTests.add(xmlFailure);
                }
            }
        }
        boolean success = exitCode == 0 && errors.isEmpty() && failedTests.isEmpty();
        String tailOutput = getLogTail(fullOutput);
        return new BuildResult(success, exitCode, tailOutput, errors, failedTests);
    }

    static String getLogTail(String fullOutput) {
        String sanitized = stripAnsiCodes(fullOutput);
        if (sanitized.isEmpty()) {
            return "";
        }
        if (sanitized.length() <= MAX_LOG_TAIL_BYTES) {
            return sanitized;
        }
        return LOG_TRUNCATED_PREFIX + sanitized.substring(sanitized.length() - MAX_LOG_TAIL_BYTES);
    }

    List<String> extractWithRipGrep(String regex, Path logFile, int contextLines) {
        if (regex == null || regex.isBlank() || logFile == null || !Files.exists(logFile)) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--color=never");
        command.add("-N");
        if (contextLines > 0) {
            command.add("-A");
            command.add(String.valueOf(contextLines));
        }
        command.add("-e");
        command.add(regex);
        command.add(logFile.toString());

        ProcessResult result = ProcessRunner.run(command, null, false);
        if (result.timedOut() || !result.isSuccess()) {
            return List.of();
        }
        return parseRipGrepOutput(result.output(), contextLines);
    }

    static List<String> parseRipGrepOutput(String rawOutput, int contextLines) {
        String sanitized = stripAnsiCodes(rawOutput);
        if (sanitized.isBlank()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        if (contextLines <= 0) {
            for (String line : sanitized.lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !blocks.contains(trimmed)) {
                    blocks.add(trimmed);
                    if (blocks.size() >= MAX_EXTRACTED_BLOCKS) {
                        break;
                    }
                }
            }
            return Collections.unmodifiableList(blocks);
        }

        List<String> currentBlockLines = new ArrayList<>();
        for (String line : sanitized.lines().toList()) {
            if (RG_BLOCK_SEPARATOR.equals(StringUtils.stripEnd(line, null))) {
                addFinalizedBlock(blocks, currentBlockLines);
                currentBlockLines.clear();
                if (blocks.size() >= MAX_EXTRACTED_BLOCKS) {
                    return Collections.unmodifiableList(blocks);
                }
            } else {
                currentBlockLines.add(line);
            }
        }
        if (blocks.size() < MAX_EXTRACTED_BLOCKS) {
            addFinalizedBlock(blocks, currentBlockLines);
        }
        return Collections.unmodifiableList(blocks);
    }

    private static void addFinalizedBlock(List<String> blocks, List<String> rawBlockLines) {
        int start = 0;
        int end = rawBlockLines.size();
        while (start < end && rawBlockLines.get(start).isBlank()) {
            start++;
        }
        while (end > start && rawBlockLines.get(end - 1).isBlank()) {
            end--;
        }
        if (start >= end) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(StringUtils.stripEnd(rawBlockLines.get(i), null));
        }
        String block = sb.toString();
        if (!block.isEmpty() && !blocks.contains(block)) {
            blocks.add(block);
        }
    }

    List<String> extractJunitXmlFailures(List<String> configuredPaths, long commandStartTimeMillis) {
        if (!projectService.hasActiveProject() || configuredPaths == null || configuredPaths.isEmpty()) {
            return List.of();
        }
        Path projectRoot = projectService.getCurrentProjectRoot();
        if (projectRoot == null || !Files.exists(projectRoot)) {
            return List.of();
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        long thresholdMillis = commandStartTimeMillis - JUNIT_XML_CLOCK_SKEW_MS;
        List<Path> xmlFiles = discoverRecentJunitXmlFiles(normalizedRoot, configuredPaths, thresholdMillis);
        if (xmlFiles.isEmpty()) {
            return List.of();
        }

        List<String> failures = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilder builder = createSafeDocumentBuilder();
            for (Path xmlFile : xmlFiles) {
                parseJunitXmlFile(builder, xmlFile, failures);
                if (failures.size() >= MAX_EXTRACTED_BLOCKS) {
                    break;
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            log.warn("Failed to configure JUnit XML parser: {}", e.getMessage());
        }
        return Collections.unmodifiableList(failures);
    }

    private static List<Path> discoverRecentJunitXmlFiles(Path normalizedRoot, List<String> configuredPaths,
            long thresholdMillis) {
        List<Path> discovered = new ArrayList<>();
        for (String rawPath : configuredPaths) {
            if (rawPath == null || rawPath.isBlank()) {
                continue;
            }
            Path candidate = normalizedRoot.resolve(rawPath.trim()).normalize();
            if (!candidate.startsWith(normalizedRoot) || !Files.exists(candidate)) {
                continue;
            }
            if (Files.isRegularFile(candidate)) {
                if (isRecentXmlFile(candidate, thresholdMillis) && !discovered.contains(candidate)) {
                    discovered.add(candidate);
                }
                continue;
            }
            if (Files.isDirectory(candidate)) {
                collectRecentXmlFilesInDirectory(candidate, thresholdMillis, discovered);
            }
        }
        return discovered;
    }

    private static void collectRecentXmlFilesInDirectory(Path directory, long thresholdMillis, List<Path> target) {
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            List<Path> matched = walk.filter(Files::isRegularFile)
                    .filter(path -> isRecentXmlFile(path, thresholdMillis)).sorted().toList();
            for (Path path : matched) {
                if (!target.contains(path)) {
                    target.add(path);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to scan JUnit XML directory {}: {}", directory, e.getMessage());
        }
    }

    private static boolean isRecentXmlFile(Path file, long thresholdMillis) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String nameLower = fileName.toString().toLowerCase(java.util.Locale.ROOT);
        if (!nameLower.endsWith(".xml")) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis() >= thresholdMillis;
        } catch (IOException e) {
            return false;
        }
    }

    private static javax.xml.parsers.DocumentBuilder createSafeDocumentBuilder()
            throws javax.xml.parsers.ParserConfigurationException {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static void parseJunitXmlFile(javax.xml.parsers.DocumentBuilder builder, Path xmlFile,
            List<String> failures) {
        try (java.io.InputStream in = Files.newInputStream(xmlFile)) {
            org.w3c.dom.Document doc = builder.parse(in);
            org.w3c.dom.NodeList testCases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                org.w3c.dom.Node node = testCases.item(i);
                if (node instanceof org.w3c.dom.Element testCase) {
                    extractTestCaseFailures(testCase, failures);
                    if (failures.size() >= MAX_EXTRACTED_BLOCKS) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Skipping unreadable JUnit XML file {}: {}", xmlFile, e.getMessage());
        }
    }

    private static void extractTestCaseFailures(org.w3c.dom.Element testCase, List<String> failures) {
        String className = StringUtils.trimToEmpty(testCase.getAttribute("classname"));
        String testName = StringUtils.trimToEmpty(testCase.getAttribute("name"));
        String testId = formatTestCaseIdentifier(className, testName);
        org.w3c.dom.NodeList children = testCase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child instanceof org.w3c.dom.Element childElem) {
                String tag = childElem.getTagName();
                if (TAG_FAILURE.equals(tag) || TAG_ERROR.equals(tag)) {
                    String formatted = formatJunitFailureElement(testId, tag, childElem);
                    if (!formatted.isEmpty() && !failures.contains(formatted)) {
                        failures.add(formatted);
                        if (failures.size() >= MAX_EXTRACTED_BLOCKS) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static String formatTestCaseIdentifier(String className, String testName) {
        if (!className.isEmpty() && !testName.isEmpty()) {
            return className + "#" + testName;
        }
        if (!testName.isEmpty()) {
            return testName;
        }
        if (!className.isEmpty()) {
            return className;
        }
        return "unknown";
    }

    private static String formatJunitFailureElement(String testId, String tag, org.w3c.dom.Element element) {
        String type = stripAnsiCodes(element.getAttribute("type")).trim();
        String message = stripAnsiCodes(element.getAttribute("message")).replace("\r\n", "\n").trim();
        String body = stripAnsiCodes(element.getTextContent());

        StringBuilder sb = new StringBuilder();
        sb.append(testId).append(" [").append(tag.toUpperCase(java.util.Locale.ROOT)).append(']');
        String headerDetail = formatFailureHeaderDetail(type, message);
        if (!headerDetail.isEmpty()) {
            sb.append(": ").append(headerDetail);
        }

        if (!body.isBlank()) {
            int appendedLines = 0;
            for (String rawLine : body.lines().toList()) {
                String trimmedLine = rawLine.trim();
                if (trimmedLine.isEmpty()) {
                    continue;
                }
                if (appendedLines == 0 && (trimmedLine.equals(headerDetail) || trimmedLine.equals(message))) {
                    continue;
                }
                sb.append('\n').append("  ").append(trimmedLine);
                appendedLines++;
                if (appendedLines >= MAX_JUNIT_FAILURE_LINES) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static String formatFailureHeaderDetail(String type, String message) {
        if (!type.isEmpty() && !message.isEmpty()) {
            if (message.startsWith(type)) {
                return message;
            }
            return type + ": " + message;
        }
        if (!type.isEmpty()) {
            return type;
        }
        return message;
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
