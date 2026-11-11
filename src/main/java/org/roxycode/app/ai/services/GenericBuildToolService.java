package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.util.ast.Node;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.SandboxConfig;
import org.roxycode.app.ai.services.BuildContainerService.SandboxMode;
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingHtmlUtils;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/**
 * Executes project build, test, format, and lint commands configured in {@code build.toml} across host, native sandbox,
 * or Docker environments, and automatically updates plan verification gates on compilation/test completion.
 */
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
    public static final List<String> DEFAULT_JUNIT_XML_PATHS = List.of("target/surefire-reports",
            "target/failsafe-reports", "build/test-results");
    static final String LOG_TRUNCATED_PREFIX = "... [Log truncated. Full log saved to .roxycode/tmp/build.log]\n";
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final String DEFAULT_FALLBACK_TOML = """
            timeout_seconds = 600

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
            # Additional host paths mounted read-only in "native" mode (supports ~/ expansion)
            readable_paths = []
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

    public record CommandsSection(String install, String clean, String compile, String build,
            @JsonProperty("test_all") String testAll, @JsonProperty("test_single") String testSingle, String lint,
            String format) {
        public static final CommandsSection EMPTY = new CommandsSection(null, null, null, null, null, null, null, null);
    }

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

    public record BuildConfig(CommandsSection commands, Map<String, String> env, ParseSection parse,
            SandboxConfig sandbox, @JsonProperty("timeout_seconds") Integer timeoutSeconds) {
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
            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            }
        }
    }

    static final String NO_RELATED_TESTS_OUTPUT = "No related tests found for changed files.";

    private final ProjectService projectService;
    private final TomlMapper tomlMapper;
    private final BuildContainerService buildContainerService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlanService planService;
    private final GitService gitService;
    private final SymbolService symbolService;

    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper,
            BuildContainerService buildContainerService, @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable PlanService planService) {
        this(projectService, tomlMapper, buildContainerService, eventPublisher, planService, null, null);
    }

    @Autowired
    public GenericBuildToolService(ProjectService projectService, TomlMapper tomlMapper,
            BuildContainerService buildContainerService, @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable PlanService planService, @Nullable GitService gitService, @Nullable SymbolService symbolService) {
        this.projectService = projectService;
        this.tomlMapper = tomlMapper;
        this.buildContainerService = buildContainerService;
        this.eventPublisher = eventPublisher;
        this.planService = planService;
        this.gitService = gitService;
        this.symbolService = symbolService;
        if (this.projectService.hasActiveProject()) {
            ensureConfig();
        }
    }

    @EventListener
    public void handleProjectChanged(ProjectChangedEvent event) {
        if (SwingUtilities.isEventDispatchThread()) {
            CompletableFuture.runAsync(this::performProjectChanged, Edt.VIRTUAL_EXECUTOR);
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

    private void stopActiveContainer() {
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
        return runTestsAndLint(null);
    }

    @AgentDoc(value = "Runs a single test using the configured test_single command. {testName} is replaced with the argument.", examples = "buildToolService.runSingleTest('com.example.TestClass')")
    public BuildResult runSingleTest(
            @AgentDoc("The name of the test class or method (e.g., MyTest or MyTest#myMethod)") String testName) {
        return runCommand("commands.test_single", config -> config.commands().testSingle(),
                Map.of("testName", StringUtils.defaultString(testName)));
    }

    @AgentDoc(value = "Discovers and runs unit tests related to currently modified and untracked files in Git using test_single for fast intermediate verification.", examples = "buildToolService.runRelatedTests()")
    public BuildResult runRelatedTests() {
        if (!projectService.hasActiveProject()) {
            return new BuildResult(false, -1, "", List.of("No active project"), List.of());
        }
        if (gitService == null) {
            return new BuildResult(true, 0, NO_RELATED_TESTS_OUTPUT, List.of(), List.of());
        }
        return runRelatedTests(gitService.getChangedFiles());
    }

    @AgentDoc(value = "Discovers and runs unit tests related to the specified file paths using test_single for fast intermediate verification.", examples = "buildToolService.runRelatedTests(['src/main/java/App.java'])")
    public BuildResult runRelatedTests(@AgentDoc("File paths relative to the project root") String... relativePaths) {
        if (relativePaths == null || relativePaths.length == 0) {
            return runRelatedTests(List.of());
        }
        return runRelatedTests(Arrays.asList(relativePaths));
    }

    @AgentDoc(value = "Discovers and runs unit tests related to the specified list of file paths using test_single for fast intermediate verification.", examples = "buildToolService.runRelatedTests(gitService.getChangedFiles())")
    public BuildResult runRelatedTests(
            @AgentDoc("List of file paths relative to the project root") List<String> relativePaths) {
        if (!projectService.hasActiveProject()) {
            return new BuildResult(false, -1, "", List.of("No active project"), List.of());
        }
        Set<String> testNames = collectRelatedTestNames(relativePaths);
        if (testNames.isEmpty()) {
            return new BuildResult(true, 0, NO_RELATED_TESTS_OUTPUT, List.of(), List.of());
        }
        StringBuilder combinedOutput = new StringBuilder();
        for (String testName : testNames) {
            BuildResult singleResult = runSingleTest(testName);
            if (StringUtils.isNotEmpty(singleResult.output())) {
                if (!combinedOutput.isEmpty()) {
                    combinedOutput.append(StringUtils.LF);
                }
                combinedOutput.append(singleResult.output());
            }
            if (!singleResult.success()) {
                if (planService != null) {
                    planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, false);
                }
                return new BuildResult(false, singleResult.exitCode(), combinedOutput.toString(), singleResult.errors(),
                        singleResult.failedTests(), singleResult.timedOut());
            }
        }
        return new BuildResult(true, 0, combinedOutput.toString(), List.of(), List.of());
    }

    private Set<String> collectRelatedTestNames(List<String> relativePaths) {
        Set<String> testNames = new LinkedHashSet<>();
        if (relativePaths == null || relativePaths.isEmpty()) {
            return testNames;
        }
        for (String rawPath : relativePaths) {
            String normalized = PathUtils.normalizeRelativePath(rawPath);
            if (StringUtils.isBlank(normalized)) {
                continue;
            }
            if (ProjectAnalysisService.isTestFile(normalized)) {
                String stem = PathUtils.getBaseName(normalized);
                if (!stem.isEmpty()) {
                    testNames.add(stem);
                }
            } else if (symbolService != null) {
                List<String> relatedFiles = symbolService.findRelatedTests(normalized);
                if (relatedFiles != null) {
                    for (String relatedFile : relatedFiles) {
                        String stem = PathUtils.getBaseName(relatedFile);
                        if (!stem.isEmpty()) {
                            testNames.add(stem);
                        }
                    }
                }
            }
        }
        return testNames;
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
        return runTestsAndLint(compileResult.output());
    }

    private BuildResult runTestsAndLint(String compileOutput) {
        BuildResult testResult = runCommand("commands.test_all", config -> config.commands().testAll());
        if (!testResult.success()) {
            if (planService != null) {
                planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, false);
            }
            if (compileOutput == null) {
                return testResult;
            }
            return new BuildResult(false, testResult.exitCode(), """
                    COMPILE OUTPUT:
                    %s

                    TEST OUTPUT:
                    %s""".formatted(compileOutput, testResult.output()), testResult.errors(), testResult.failedTests(),
                    testResult.timedOut());
        }
        if (compileOutput == null && planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        }
        BuildResult lintResult = lint();
        if (planService != null) {
            planService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, lintResult.success());
        }
        if (compileOutput != null) {
            return new BuildResult(lintResult.success(), lintResult.exitCode(), """
                    COMPILE OUTPUT:
                    %s

                    TEST OUTPUT:
                    %s

                    LINT OUTPUT:
                    %s""".formatted(compileOutput, testResult.output(), lintResult.output()), lintResult.errors(),
                    lintResult.failedTests(), testResult.timedOut() || lintResult.timedOut());
        }
        if (!lintResult.success()) {
            return new BuildResult(false, lintResult.exitCode(), """
                    TEST OUTPUT:
                    %s

                    LINT OUTPUT:
                    %s""".formatted(testResult.output(), lintResult.output()), lintResult.errors(),
                    lintResult.failedTests(), testResult.timedOut() || lintResult.timedOut());
        }
        return testResult;
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
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new AgentActivityEvent("",
                        "Running build command (" + commandLabel + "): " + StringUtils.abbreviate(command.trim(), 60)));
            }
            ParseSection parseSection = config.parse();
            Map<String, String> env = config.env();
            SandboxConfig sandboxConfig = config.sandbox();
            SandboxMode mode = sandboxConfig.resolvedMode();
            Path logFile = prepareBuildLogFile();
            Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
            if (mode == SandboxMode.DOCKER) {
                if (!buildContainerService.isDockerAvailable()) {
                    stopActiveContainer();
                    String msg = "Error executing " + commandLabel
                            + ": Docker is not available, but [sandbox] mode = \"docker\" is configured in build.toml.";
                    return new BuildResult(false, -1, msg, List.of(msg), List.of());
                }
                return executeInContainer(command, sandboxConfig, env, logFile, parseSection, timeout);
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
                return executeCommandList(sandboxCmd, env, logFile, parseSection, timeout);
            }
            stopActiveContainer();
            return execute(command, env, logFile, parseSection, timeout);
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
        String templateId = detectTemplate();
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            try {
                PathUtils.createParentDirectories(configPath);
                Files.writeString(configPath, loadTemplateContent(templateId), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to initialize build config", e);
                return;
            }
        }
        if (projectService.hasActiveProject() && !projectService.isBuildConfirmed() && !"default".equals(templateId)) {
            projectService.setBuildConfirmed(true);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new SettingsChangedEvent(null));
            }
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
        return parseConfig(getConfigContent());
    }

    BuildConfig parseConfig(String rawToml) throws IOException {
        return parseConfig(rawToml, tomlMapper);
    }

    static BuildConfig parseConfig(String rawToml, TomlMapper mapper) throws IOException {
        String content = StringUtils.defaultString(rawToml);
        if (isBlankOrCommentsOnlyToml(content)) {
            throw new IOException("Invalid build.toml: configuration cannot be empty.");
        }
        BuildConfig config;
        try {
            config = mapper.readValue(BuildOutputParser.sanitizeTomlEscapes(content), BuildConfig.class);
        } catch (JacksonException e) {
            throw new IOException(formatTomlValidationError(e), e);
        }
        if (config == null) {
            throw new IOException("Invalid build.toml: configuration cannot be empty.");
        }
        String rawMode = config.sandbox().mode();
        if (!SandboxMode.isValidMode(rawMode)) {
            throw new IOException("Invalid build.toml: unknown [sandbox] mode '" + rawMode
                    + "'. Expected 'none', 'native', or 'docker'.");
        }
        return config;
    }

    BuildConfig validateConfig(String rawToml) throws IOException {
        BuildConfig config = parseConfig(rawToml);
        validateSandboxSupport(config);
        return config;
    }

    private void validateSandboxSupport(BuildConfig config) throws IOException {
        if (buildContainerService == null) {
            return;
        }
        SandboxMode mode = config.sandbox().resolvedMode();
        if (mode == SandboxMode.NATIVE && !buildContainerService.isNativeSandboxAvailable()) {
            throw new IOException("Unsupported [sandbox] mode 'native': native OS sandbox tool (Bubblewrap or Seatbelt)"
                    + " is not installed or detected. Install the tool or use mode = 'none'.");
        }
        if (mode == SandboxMode.DOCKER && !buildContainerService.isDockerAvailable()) {
            throw new IOException("Unsupported [sandbox] mode 'docker': Docker is not installed or the Docker daemon"
                    + " is not running. Start Docker or use mode = 'none'.");
        }
    }

    static boolean isBlankOrCommentsOnlyToml(@Nullable String content) {
        if (StringUtils.isBlank(content)) {
            return true;
        }
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return false;
            }
        }
        return true;
    }

    static String formatTomlValidationError(JacksonException e) {
        if (e instanceof UnrecognizedPropertyException unrecognized) {
            return "Invalid build.toml: unrecognized property '" + unrecognized.getPropertyName() + "'"
                    + formatLocationSuffix(unrecognized.getLocation()) + ".";
        }
        if (e instanceof MismatchedInputException mismatched) {
            String originalMessage = StringUtils.defaultIfBlank(mismatched.getOriginalMessage(), "invalid value type");
            return "Invalid build.toml: " + originalMessage + formatLocationSuffix(mismatched.getLocation());
        }
        String originalMessage = StringUtils.defaultIfBlank(e.getOriginalMessage(), e.getMessage());
        return "Invalid build.toml syntax" + formatLocationSuffix(e.getLocation()) + ": " + originalMessage;
    }

    private static String formatLocationSuffix(@Nullable JsonLocation location) {
        if (location != null && location.getLineNr() > 0) {
            return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
        }
        return "";
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private BuildResult executeInContainer(String command, SandboxConfig sandboxConfig, Map<String, String> env,
            Path logFile, ParseSection parseSection, Duration timeout) {
        log.info("Executing build command in container ({}): {}", sandboxConfig.image(), command);
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput;
        long startTimeMillis = System.currentTimeMillis();

        try {
            ContainerExecResult result = buildContainerService.executeCommand(projectService.getCurrentProjectRoot(),
                    sandboxConfig, env, command, timeout);
            fullOutput = BuildOutputParser.stripAnsiCodes(result.combinedOutput());
            exitCode = result.exitCode();
            Files.writeString(logFile, fullOutput, StandardCharsets.UTF_8);
            if (result.timedOut()) {
                errors.add("Command timed out after " + timeout.toSeconds() + " seconds");
            }
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis,
                    result.timedOut());
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
            String errorMsg = BuildOutputParser
                    .stripAnsiCodes(org.roxycode.app.util.ExceptionUtils.getMessageOrSimpleName(e));
            errors.add("Container execution failed: " + errorMsg);
            fullOutput = errorMsg;
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis,
                    false);
        }
    }

    private BuildResult execute(String command, Map<String, String> env, Path logFile, ParseSection parseSection,
            Duration timeout) {
        log.info("Executing build command: {}", command);
        List<String> shellCommand;
        if (SystemUtils.IS_OS_WINDOWS) {
            shellCommand = List.of("cmd.exe", "/c", command);
        } else {
            shellCommand = List.of("sh", "-c", command);
        }
        return executeCommandList(shellCommand, env, logFile, parseSection, timeout);
    }

    private BuildResult executeCommandList(List<String> commandList, Map<String, String> env, Path logFile,
            ParseSection parseSection, Duration timeout) {
        List<String> errors = new ArrayList<>();
        int exitCode = -1;
        String fullOutput = "";
        long startTimeMillis = System.currentTimeMillis();

        try {
            ProcessResult result;
            try (OutputStream fileOut = Files.newOutputStream(logFile)) {
                result = ProcessRunner.run(commandList, projectService.getCurrentProjectRoot(), env, timeout, true,
                        fileOut);
            }
            fullOutput = BuildOutputParser.stripAnsiCodes(result.output());
            Files.writeString(logFile, fullOutput, StandardCharsets.UTF_8);
            exitCode = result.exitCode();
            if (result.timedOut()) {
                errors.add("Command timed out after " + timeout.toSeconds() + " seconds");
            } else if (exitCode < 0 && !result.errorOutput().isBlank()) {
                errors.add(BuildOutputParser.stripAnsiCodes(result.errorOutput()));
            }
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis,
                    result.timedOut());
        } catch (IOException e) {
            log.error("Process execution failed", e);
            errors.add(ExceptionUtils.getStackTrace(e));
            return buildResultFromExecution(exitCode, fullOutput, logFile, errors, parseSection, startTimeMillis,
                    false);
        }
    }

    private BuildResult buildResultFromExecution(int exitCode, String fullOutput, Path logFile, List<String> errors,
            ParseSection parseSection, long startTimeMillis, boolean timedOut) {
        ParseSection effectiveParse = Objects.requireNonNullElse(parseSection, ParseSection.EMPTY);
        int contextLines = effectiveParse.resolvedContextLines();
        errors.addAll(BuildOutputParser.extractWithRipGrep(effectiveParse.errorRegex(), logFile, contextLines));
        if (projectService.hasActiveProject() && !errors.isEmpty()) {
            List<String> enrichedErrors = BuildOutputParser.enrichErrorsWithSourceContext(errors,
                    projectService.getCurrentProjectRoot());
            errors.clear();
            errors.addAll(enrichedErrors);
        }
        List<String> failedTests = new ArrayList<>(
                BuildOutputParser.extractWithRipGrep(effectiveParse.testFailureRegex(), logFile, contextLines));
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
        String tailOutput = BuildOutputParser.getLogTail(fullOutput);
        return new BuildResult(success, exitCode, tailOutput, errors, failedTests, timedOut);
    }

    List<String> extractJunitXmlFailures(List<String> configuredPaths, long commandStartTimeMillis) {
        if (!projectService.hasActiveProject()) {
            return List.of();
        }
        return BuildOutputParser.extractJunitXmlFailures(projectService.getCurrentProjectRoot(), configuredPaths,
                commandStartTimeMillis);
    }

    public String getConfigContent() throws IOException {
        if (!projectService.hasActiveProject()) {
            return "";
        }
        Path configPath = getConfigPath();
        ensureConfig();
        if (Files.exists(configPath)) {
            try {
                return Files.readString(configPath, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                log.warn("Build config file not found at {}, falling back to template", configPath);
            }
        }
        return loadDefaultTemplateContent();
    }

    private static final int MAX_BUILD_CONTEXT_FILE_CHARS = 8_000;
    private static final int MAX_DISCOVERED_JDKS = 25;
    private static final int MAX_ROOT_LISTING_ENTRIES = 60;
    private static final List<String> BUILD_CONTEXT_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", "gradle.properties", ".mvn/jvm.config", "package.json",
            "pyproject.toml", "requirements.txt", "Cargo.toml", "rust-toolchain.toml", "go.mod", "CMakeLists.txt",
            "Makefile", ".sdkmanrc", ".java-version", ".nvmrc", ".node-version", ".python-version", ".tool-versions",
            "mise.toml");

    /**
     * Collects project build files, version files, host environment/toolchain hints, and the selected template to
     * provide rich context for AI-assisted {@code build.toml} generation.
     *
     * @param templateId
     *            the selected or detected template ID (e.g., {@code "maven"}, {@code "gradle"})
     * @param currentEditorToml
     *            the current {@code build.toml} content in the editor (optional)
     * @return a structured Markdown/text summary of project and host build context
     */
    public String collectBuildConfigurationContext(@Nullable String templateId, @Nullable String currentEditorToml) {
        String effectiveTemplateId = StringUtils.defaultIfBlank(templateId, detectTemplate());
        String templateContent = loadTemplateContent(effectiveTemplateId);
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("## Target Build Template (").append(effectiveTemplateId).append(')').append(nl).append("~~~toml")
                .append(nl).append(templateContent.trim()).append(nl).append("~~~").append(nl).append(nl);

        if (StringUtils.isNotBlank(currentEditorToml)
                && !StringUtils.equals(currentEditorToml.trim(), templateContent.trim())) {
            sb.append("## Current Editor build.toml").append(nl).append("~~~toml").append(nl)
                    .append(StringUtils.abbreviate(currentEditorToml.trim(), MAX_BUILD_CONTEXT_FILE_CHARS)).append(nl)
                    .append("~~~").append(nl).append(nl);
        }

        appendHostEnvironmentSection(sb);
        if (projectService.hasActiveProject()) {
            appendProjectFilesSection(sb, projectService.getCurrentProjectRoot());
        }
        return sb.toString();
    }

    private void appendHostEnvironmentSection(StringBuilder sb) {
        String nl = System.lineSeparator();
        sb.append("## Host Environment & Detected Toolchains").append(nl);
        sb.append("- OS: ").append(SystemUtils.OS_NAME).append(" (").append(SystemUtils.OS_ARCH).append(')').append(nl);
        String userHome = System.getProperty("user.home", "");
        sb.append("- User Home: ").append(userHome).append(nl);
        sb.append("- RoxyCode Runtime Java: ").append(System.getProperty("java.version", "")).append(" (")
                .append(System.getProperty("java.home", "")).append(')').append(nl);
        String envJavaHome = System.getenv("JAVA_HOME");
        if (StringUtils.isNotBlank(envJavaHome)) {
            sb.append("- Host JAVA_HOME env: ").append(envJavaHome).append(nl);
        }
        boolean nativeSandbox = buildContainerService != null && buildContainerService.isNativeSandboxAvailable();
        boolean dockerAvailable = buildContainerService != null && buildContainerService.isDockerAvailable();
        sb.append("- Native Sandbox Available (bwrap / sandbox-exec): ").append(nativeSandbox).append(nl);
        sb.append("- Docker Available: ").append(dockerAvailable).append(nl);

        List<String> jdkDirs = discoverHostJdkDirectories(userHome);
        if (!jdkDirs.isEmpty()) {
            sb.append("- Installed Host JDKs:").append(nl);
            for (String jdk : jdkDirs) {
                sb.append("  - ").append(jdk).append(nl);
            }
        }
        List<String> toolchainRoots = discoverHostToolchainDirectories(userHome);
        if (!toolchainRoots.isEmpty()) {
            sb.append("- Existing User Toolchain / Cache Directories:").append(nl);
            for (String dir : toolchainRoots) {
                sb.append("  - ").append(dir).append(nl);
            }
        }
        sb.append(nl);
    }

    static List<String> discoverHostJdkDirectories(@Nullable String userHome) {
        List<Path> searchRoots = new ArrayList<>();
        if (StringUtils.isNotBlank(userHome)) {
            Path home = Path.of(userHome);
            searchRoots.add(home.resolve(".sdkman/candidates/java"));
            searchRoots.add(home.resolve(".jdks"));
            searchRoots.add(home.resolve(".asdf/installs/java"));
            searchRoots.add(home.resolve(".local/share/mise/installs/java"));
            searchRoots.add(home.resolve(".jabba/jdk"));
        }
        searchRoots.add(Path.of("/Library/Java/JavaVirtualMachines"));
        searchRoots.add(Path.of("/usr/lib/jvm"));
        searchRoots.add(Path.of("/opt/java"));
        searchRoots.add(Path.of("/opt/jdk"));

        Set<String> discovered = new LinkedHashSet<>();
        for (Path root : searchRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> stream = Files.list(root)) {
                List<Path> children = stream.sorted().toList();
                for (Path child : children) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    Path contentsHome = child.resolve("Contents/Home");
                    if (Files.isDirectory(contentsHome)) {
                        discovered.add(contentsHome.toAbsolutePath().normalize().toString());
                    } else {
                        discovered.add(child.toAbsolutePath().normalize().toString());
                    }
                    if (discovered.size() >= MAX_DISCOVERED_JDKS) {
                        return List.copyOf(discovered);
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to list JDK directory {}: {}", root, e.getMessage());
            }
        }
        return List.copyOf(discovered);
    }

    private static List<String> discoverHostToolchainDirectories(@Nullable String userHome) {
        if (StringUtils.isBlank(userHome)) {
            return List.of();
        }
        Path home = Path.of(userHome);
        List<String> candidates = List.of(".sdkman", ".jdks", ".m2", ".gradle", ".nvm", ".fnm", ".volta", ".npm",
                ".local/share/pnpm", ".yarn", ".pyenv", ".local", ".cache/pip", ".cache/uv", ".rustup", ".cargo", "sdk",
                "go/pkg", ".cache/go-build", ".asdf", ".mise");
        List<String> existing = new ArrayList<>();
        for (String rel : candidates) {
            if (Files.exists(home.resolve(rel))) {
                existing.add("~/" + rel);
            }
        }
        return List.copyOf(existing);
    }

    private static void appendProjectFilesSection(StringBuilder sb, Path projectRoot) {
        String nl = System.lineSeparator();
        sb.append("## Project Root Directory Listing").append(nl);
        try (java.util.stream.Stream<Path> stream = Files.list(projectRoot)) {
            List<String> names = stream.map(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    return name + "/";
                }
                return name;
            }).sorted().limit(MAX_ROOT_LISTING_ENTRIES).toList();
            sb.append(String.join(", ", names)).append(nl).append(nl);
        } catch (IOException e) {
            sb.append("(Unable to list directory)").append(nl).append(nl);
        }

        sb.append("## Project Build & Version Files").append(nl);
        for (String relPath : BUILD_CONTEXT_FILES) {
            Path file = projectRoot.resolve(relPath);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (StringUtils.isNotBlank(content)) {
                    sb.append("### ").append(relPath).append(nl).append("~~~").append(nl)
                            .append(StringUtils.abbreviate(content.trim(), MAX_BUILD_CONTEXT_FILE_CHARS)).append(nl)
                            .append("~~~").append(nl).append(nl);
                }
            } catch (IOException e) {
                log.debug("Skipping unreadable build context file {}: {}", relPath, e.getMessage());
            }
        }
    }

    /**
     * Builds an interactive prompt for Ask Roxy ({@code EXPLORE} phase) containing the detected build template, current
     * editor {@code build.toml}, host environment/toolchains, and project build files, instructing Roxy to ask
     * clarifying questions via {@code userInputService} before generating the final fenced {@code build.toml} block.
     *
     * @param templateId
     *            the target template identifier (or {@code null} to auto-detect)
     * @param currentEditorToml
     *            the current {@code build.toml} content in the editor (optional)
     * @return the interactive configuration prompt string
     */
    public String buildInteractiveConfigurationPrompt(@Nullable String templateId, @Nullable String currentEditorToml) {
        String context = collectBuildConfigurationContext(templateId, currentEditorToml);
        String nl = System.lineSeparator();
        String backtickFence = "~~~".replace('~', (char) 96);
        StringBuilder sb = new StringBuilder(context.length() + 1200);
        sb.append("Please help me configure build.toml for this project.").append(nl).append(nl);
        sb.append("### Interactive Configuration Instructions").append(nl);
        sb.append(
                "1. **Inspect Context**: Review the host environment, detected JDKs/toolchains, sandbox availability, ")
                .append("and project build files below (use fileReadService if you need to inspect any additional project files).")
                .append(nl);
        sb.append(
                "2. **Ask Clarifying Questions First**: Before generating the final build.toml, use userInputService ")
                .append("(selectOption, askText, or confirm) via executeJexl to ask me about any meaningful configuration choices up front:")
                .append(nl);
        sb.append(
                "   - **Sandbox Mode**: If multiple execution modes are available (e.g., Native Sandbox bwrap/sandbox-exec, Docker, or host 'none'), ask which sandbox mode I prefer.")
                .append(nl);
        sb.append(
                "   - **Toolchain / Runtime & Custom Flags**: If multiple JDKs, toolchains, Docker images, or custom build/test/lint flags are relevant, ask for my preference.")
                .append(nl);
        sb.append(
                "3. **Output Complete build.toml**: Once I answer, output the complete, tailored build.toml inside a single fenced ")
                .append(backtickFence).append("toml")
                .append(" code block (including [commands], [env], [parse], and [sandbox]), ")
                .append("and remind me that I can click **[Apply to build.toml]** on your message to apply it directly to **Project Settings**.")
                .append(nl).append(nl);
        sb.append(context);
        return sb.toString();
    }

    /**
     * Scans a Markdown message for fenced code blocks and returns the last valid {@code build.toml} block that contains
     * a {@code [commands]} or {@code [sandbox]} section and parses cleanly into a {@link BuildConfig}.
     *
     * @param markdown
     *            the raw Markdown message text
     * @return the normalized TOML content ending with a line separator, or {@code null} if no valid {@code build.toml}
     *         block is present
     */
    public static @Nullable String extractValidBuildTomlBlock(@Nullable String markdown) {
        if (StringUtils.isBlank(markdown)) {
            return null;
        }
        List<String> blocks = extractFencedCodeBlocks(markdown);
        if (blocks.isEmpty()) {
            return null;
        }
        String nl = System.lineSeparator();
        String validToml = null;
        for (String block : blocks) {
            String trimmed = block.trim();
            if (!containsBuildTomlSectionHeader(trimmed)) {
                continue;
            }
            try {
                parseConfig(trimmed, AiConfig.SHARED_TOML_MAPPER);
                if (trimmed.endsWith(nl)) {
                    validToml = trimmed;
                } else {
                    validToml = trimmed + nl;
                }
            } catch (IOException ignored) {
                // Not a valid build.toml block
            }
        }
        return validToml;
    }

    private static boolean containsBuildTomlSectionHeader(String tomlContent) {
        for (String line : tomlContent.lines().toList()) {
            String stripped = line.trim();
            if ("[commands]".equals(stripped) || "[sandbox]".equals(stripped)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> extractFencedCodeBlocks(String markdown) {
        List<String> blocks = new ArrayList<>();
        Node document = SwingHtmlUtils.getMarkdownParser().parse(markdown);
        collectFencedCodeBlocks(document, blocks);
        return blocks;
    }

    private static void collectFencedCodeBlocks(Node parent, List<String> blocks) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock fenced) {
                String info = fenced.getInfo().toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (info.isEmpty() || "toml".equals(info) || "ini".equals(info) || "build.toml".equals(info)) {
                    blocks.add(fenced.getContentChars().toString());
                }
            } else {
                collectFencedCodeBlocks(child, blocks);
            }
        }
    }

    public void saveConfigContent(String content) throws IOException {
        if (!projectService.hasActiveProject()) {
            return;
        }
        String normalizedContent = StringUtils.defaultString(content);
        validateConfig(normalizedContent);
        stopActiveContainer();
        Path configPath = getConfigPath();
        PathUtils.createParentDirectories(configPath);
        Files.writeString(configPath, normalizedContent, StandardCharsets.UTF_8);
    }
}
