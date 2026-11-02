package org.roxycode.app.ai.services;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSession;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSessionKey;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SystemToolService;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;
import org.springframework.context.ApplicationEventPublisher;

class GenericBuildToolServiceTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path projectDataDir;
    private ProjectService projectService;
    private SystemToolService systemToolService;
    private BuildContainerService buildContainerService;
    private ApplicationEventPublisher eventPublisher;
    private PlanService planService;
    private GenericBuildToolService buildToolService;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = Files.createDirectories(tempDir.resolve("workspace"));
        projectDataDir = tempDir.resolve("roxy-home/projects/workspace-12345678");
        projectService = mock(ProjectService.class);
        systemToolService = mock(SystemToolService.class);
        buildContainerService = new BuildContainerService(systemToolService);
        eventPublisher = mock(ApplicationEventPublisher.class);
        planService = mock(PlanService.class);
        lenient().when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        buildToolService = new GenericBuildToolService(projectService, new TomlMapper(), buildContainerService,
                eventPublisher, planService);

        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        lenient().when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
        lenient().when(projectService.resolveRelativePath(anyString())).thenAnswer(inv -> {
            String arg = inv.getArgument(0, String.class);
            if (arg != null && arg.contains("..")) {
                throw new SecurityException("Path traversal attempt detected: " + arg);
            }
            if (arg == null || arg.isBlank() || ".".equals(arg.trim())) {
                return ".";
            }
            String trimmed = arg.trim().replace((char) 92, '/');
            while (trimmed.startsWith("./")) {
                trimmed = trimmed.substring(2);
            }
            return trimmed;
        });
    }

    private void writeBuildToml(String toml) throws IOException {
        Files.createDirectories(projectDataDir);
        Files.writeString(projectDataDir.resolve("build.toml"), toml);
    }

    private void stubDockerAvailable(boolean available) {
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(available));
    }

    private static ContainerSession createMockSession(int exitCode, String stdout, List<String[]> captured,
            Runnable onClose) {
        AtomicBoolean closed = new AtomicBoolean(false);
        return new ContainerSession() {
            @Override
            public ContainerExecResult execInContainer(String... command) {
                if (captured != null) {
                    captured.add(command);
                }
                return new ContainerExecResult(exitCode, stdout, "");
            }

            @Override
            public boolean isRunning() {
                return !closed.get();
            }

            @Override
            public void close() {
                closed.set(true);
                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    @Test
    void testEnsureConfigAutoDetectionAndTemplateLoadingIncludingRust() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");
        writeBuildToml("""
                [commands]
                compile = 'echo maven-ok'
                """);
        assertTrue(buildToolService.compile().isSuccess());

        Path configPath = projectDataDir.resolve("build.toml");
        Files.deleteIfExists(configPath);
        String mavenContent = buildToolService.getConfigContent();
        assertTrue(Files.exists(configPath));
        assertFalse(Files.exists(projectRoot.resolve(".roxycode")));
        assertContainsAll(mavenContent, "[commands]", "./mvnw test-compile", "# Examples:", "pmd:check", "[container]",
                "eclipse-temurin:25-jdk");
        GenericBuildToolService.BuildConfig parsedMaven = assertDoesNotThrow(() -> buildToolService.loadConfig());
        assertEquals("", parsedMaven.commands().lint());
        assertEquals("", parsedMaven.commands().lintSingle());
        assertEquals("", parsedMaven.commands().format());
        assertEquals("", parsedMaven.commands().formatSingle());

        Files.deleteIfExists(configPath);
        Files.deleteIfExists(projectDataDir.resolve("tmp/build.log"));
        Files.deleteIfExists(projectDataDir.resolve("tmp"));
        Files.deleteIfExists(projectDataDir);
        Files.writeString(projectDataDir, "not a directory");
        String fallbackContent = assertDoesNotThrow(() -> buildToolService.getConfigContent());
        assertContainsAll(fallbackContent, "[commands]", "./mvnw test-compile", "[container]");

        Files.deleteIfExists(projectDataDir);
        Files.deleteIfExists(projectRoot.resolve("pom.xml"));
        Files.writeString(projectRoot.resolve("Cargo.toml"), "[package]\nname = 'demo'\n");
        assertEquals("rust", buildToolService.detectTemplate());
        String rustContent = buildToolService.getConfigContent();
        assertContainsAll(rustContent, "[commands]", "cargo check --tests", "cargo clippy -- -D warnings",
                "rustfmt {file}", "rust:1.85");

        List<GenericBuildToolService.BuildTemplateInfo> availableTemplates = buildToolService.getAvailableTemplates();
        assertEquals(8, availableTemplates.size());
        assertTrue(availableTemplates.stream().anyMatch(t -> "maven".equals(t.id()) && "Maven".equals(t.toString())));
        assertTrue(availableTemplates.stream()
                .anyMatch(t -> "default".equals(t.id()) && "No-Op / Manual Setup".equals(t.displayName())));
        String defaultLoaded = buildToolService.loadTemplateContent("default");
        assertContainsAll(defaultLoaded, "[commands]", "compile = ");

        Files.deleteIfExists(configPath);
        Files.deleteIfExists(projectRoot.resolve("Cargo.toml"));
        Files.writeString(projectRoot.resolve("pyproject.toml"), "[project]");

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader emptyClassLoader = new URLClassLoader(new URL[0], null)) {
            Thread.currentThread().setContextClassLoader(emptyClassLoader);
            String pyContent = buildToolService.getConfigContent();
            assertContainsAll(pyContent, "[commands]", "pytest", "# Examples:", "ruff check {file}", "[container]",
                    "python:3.12");
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    @Test
    void testNoOpForBlankCommandsFallbacksFilePlaceholdersAndCodePhaseEnforcement() throws IOException {
        writeBuildToml("""
                [commands]
                install = ""
                clean = ""
                compile = ""
                build = ""
                test_all = ""
                test_single = ""
                lint = ""
                lint_single = ""
                format = ""
                format_single = ""
                """);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            BuildResult compileNoOp = buildToolService.compile();
            assertTrue(compileNoOp.isSuccess());
            assertEquals(0, compileNoOp.getExitCode());
            assertEquals("Command 'commands.compile' is not configured (no-op).", compileNoOp.getOutput());

            BuildResult buildNoOp = buildToolService.build();
            assertTrue(buildNoOp.isSuccess());
            assertEquals("Command 'commands.build' is not configured (no-op).", buildNoOp.getOutput());

            BuildResult installNoOp = buildToolService.install();
            assertTrue(installNoOp.isSuccess());
            assertEquals("Command 'commands.install' is not configured (no-op).", installNoOp.getOutput());

            BuildResult formatNoOp = buildToolService.format();
            assertTrue(formatNoOp.isSuccess());
            assertEquals("Command 'commands.format' is not configured (no-op).", formatNoOp.getOutput());

            BuildResult formatSingleNoOp = buildToolService.format("src/App.java");
            assertTrue(formatSingleNoOp.isSuccess());
            assertEquals("Command 'commands.format_single' is not configured (no-op).", formatSingleNoOp.getOutput());

            BuildResult lintSingleNoOp = buildToolService.lint("src/App.java");
            assertTrue(lintSingleNoOp.isSuccess());
            assertEquals("Command 'commands.lint_single' is not configured (no-op).", lintSingleNoOp.getOutput());

            runner.verifyNoInteractions();
            verifyNoInteractions(eventPublisher);
        }

        // Fallbacks: build -> compile, lint(file) -> lint, format(file) -> format
        writeBuildToml("""
                [commands]
                install = 'echo installed-deps'
                compile = 'echo fallback-compile'
                build = ''
                lint = 'echo fallback-lint'
                lint_single = ''
                format = 'echo fallback-format'
                format_single = ''
                """);

        BuildResult buildFallback = buildToolService.build();
        assertTrue(buildFallback.isSuccess());
        assertTrue(buildFallback.getOutput().contains("fallback-compile"));

        BuildResult lintFallback = buildToolService.lint("./src/Main.java");
        assertTrue(lintFallback.isSuccess());
        assertTrue(lintFallback.getOutput().contains("fallback-lint"));

        BuildResult formatFallback = buildToolService.format("./src/Main.java");
        assertTrue(formatFallback.isSuccess());
        assertTrue(formatFallback.getOutput().contains("fallback-format"));
        verify(eventPublisher).publishEvent(new FileChangedEvent("src/Main.java"));

        BuildResult installRes = buildToolService.install();
        assertTrue(installRes.isSuccess());
        assertTrue(installRes.getOutput().contains("installed-deps"));
        verify(eventPublisher).publishEvent(new FileChangedEvent(""));

        clearInvocations(eventPublisher);
        BuildResult formatAllRes = buildToolService.format();
        assertTrue(formatAllRes.isSuccess());
        verify(eventPublisher).publishEvent(new FileChangedEvent(""));

        // Direct single commands with {file} placeholder replacement
        writeBuildToml("""
                [commands]
                build = 'echo explicit-build'
                lint_single = 'echo linting-{file}'
                format_single = 'echo formatting-{file}'
                """);
        assertTrue(buildToolService.build().getOutput().contains("explicit-build"));
        assertTrue(buildToolService.lint("src/dir/Foo.java").getOutput().contains("linting-src/dir/Foo.java"));
        assertTrue(buildToolService.format("src/dir/Foo.java").getOutput().contains("formatting-src/dir/Foo.java"));

        // Path validation failures for lint(relativePath) and format(relativePath)
        BuildResult traversalLint = buildToolService.lint("../outside.java");
        assertFalse(traversalLint.isSuccess());
        assertTrue(traversalLint.getErrors().get(0).contains("Path traversal"));

        BuildResult blankFormat = buildToolService.format("   ");
        assertFalse(blankFormat.isSuccess());
        assertTrue(blankFormat.getErrors().get(0).contains("File path must not be blank"));

        // CODE phase enforcement on install and format
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        assertThrows(IllegalStateException.class, () -> buildToolService.install());
        assertThrows(IllegalStateException.class, () -> buildToolService.format());
        assertThrows(IllegalStateException.class, () -> buildToolService.format("src/Main.java"));
        assertDoesNotThrow(() -> buildToolService.lint("src/Main.java"));
    }

    @Test
    void testBuildAndTestThreeStepPipelineAndShortCircuit() throws IOException {
        writeBuildToml("""
                [commands]
                compile = 'echo step-compile'
                test_all = 'echo step-test'
                lint = 'echo step-lint'
                """);

        BuildResult allPass = buildToolService.buildAndTest();
        assertTrue(allPass.isSuccess());
        assertContainsAll(allPass.getOutput(), "COMPILE OUTPUT:", "step-compile", "TEST OUTPUT:", "step-test",
                "LINT OUTPUT:", "step-lint");

        // Short-circuit on test failure before lint runs
        writeBuildToml("""
                [commands]
                compile = 'echo step-compile'
                test_all = 'exit 2'
                lint = 'echo should-not-run-lint'
                """);
        BuildResult testFail = buildToolService.buildAndTest();
        assertFalse(testFail.isSuccess());
        assertEquals(2, testFail.getExitCode());
        assertContainsAll(testFail.getOutput(), "COMPILE OUTPUT:", "TEST OUTPUT:");
        assertFalse(testFail.getOutput().contains("should-not-run-lint"));

        // Short-circuit on compile failure before test_all runs
        writeBuildToml("""
                [commands]
                compile = 'exit 3'
                test_all = 'echo should-not-run-test'
                lint = 'echo should-not-run-lint'
                """);
        BuildResult compileFail = buildToolService.buildAndTest();
        assertFalse(compileFail.isSuccess());
        assertEquals(3, compileFail.getExitCode());
        assertFalse(compileFail.getOutput().contains("TEST OUTPUT:"));
    }

    @Test
    void testCommandPlaceholdersAndLogTailPersistence() throws IOException {
        writeBuildToml("[commands]\ntest_single = 'echo {testName}'\n");
        BuildResult result = buildToolService.runSingleTest("MyTest");
        assertTrue(result.getOutput().contains("MyTest"));
        Path persistedLog = projectDataDir.resolve("tmp/build.log");
        assertTrue(Files.exists(persistedLog));
        assertTrue(Files.readString(persistedLog).contains("MyTest"));
        assertFalse(Files.exists(projectRoot.resolve(".roxycode")));

        assertEquals("short output", GenericBuildToolService.getLogTail("short output"));

        String largeContent = "HEADER-" + "A".repeat(5000) + "-TAIL_MARKER";
        String truncated = GenericBuildToolService.getLogTail(largeContent);
        assertTrue(truncated.startsWith(GenericBuildToolService.LOG_TRUNCATED_PREFIX));
        assertFalse(truncated.contains("HEADER-"));
        assertTrue(truncated.endsWith("-TAIL_MARKER"));
        assertEquals(GenericBuildToolService.LOG_TRUNCATED_PREFIX.length() + GenericBuildToolService.MAX_LOG_TAIL_BYTES,
                truncated.length());

        assertEquals("", GenericBuildToolService.getLogTail(null));
        assertEquals("", GenericBuildToolService.getLogTail(""));
    }

    @Test
    void testContainerExecutionAndRipGrepRegexParsing() throws IOException {
        stubDockerAvailable(true);
        buildContainerService.setContainerSessionFactory(key -> createMockSession(1,
                "[ERROR] Container compile failure" + System.lineSeparator() + "FAIL: ContainerTest#fail", null, null));

        writeBuildToml("""
                [commands]
                test_all = './mvnw test'
                [parse]
                error_regex = '^.[E]RROR.'
                test_failure_regex = '^FAIL:'
                [container]
                enabled = true
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """);
        Path persistedLog = projectDataDir.resolve("tmp/build.log");
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            List<String> errCmd = List.of("rg", "--color=never", "-N", "-e", "^.[E]RROR.", persistedLog.toString());
            List<String> failCmd = List.of("rg", "--color=never", "-N", "-e", "^FAIL:", persistedLog.toString());
            runner.when(() -> ProcessRunner.run(eq(errCmd), isNull(), eq(false))).thenReturn(
                    new ProcessResult(0, "  [ERROR] Container compile failure  " + System.lineSeparator(), "", false));
            runner.when(() -> ProcessRunner.run(eq(failCmd), isNull(), eq(false)))
                    .thenReturn(new ProcessResult(0, "FAIL: ContainerTest#fail" + System.lineSeparator(), "", false));

            BuildResult result = buildToolService.runTests();
            assertFalse(result.isSuccess());
            assertEquals(1, result.getExitCode());
            assertEquals(List.of("[ERROR] Container compile failure"), result.getErrors());
            assertEquals(List.of("FAIL: ContainerTest#fail"), result.getFailedTests());

            List<String> noMatchCmd = List.of("rg", "--color=never", "-N", "-e", "^NOMATCH:", persistedLog.toString());
            runner.when(() -> ProcessRunner.run(eq(noMatchCmd), isNull(), eq(false)))
                    .thenReturn(new ProcessResult(GrepService.EXIT_CODE_NO_MATCHES, "", "", false));
            assertTrue(buildToolService.extractWithRipGrep("^NOMATCH:", persistedLog).isEmpty());
        }
    }

    @Test
    void testContainerExecutionErrorAndCancellationHandling() throws IOException {
        writeBuildToml("""
                [commands]
                compile = './mvnw test-compile'
                [container]
                enabled = true
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """);

        stubDockerAvailable(false);
        BuildResult noDockerRes = buildToolService.compile();
        assertFalse(noDockerRes.isSuccess());
        assertEquals(-1, noDockerRes.getExitCode());
        assertTrue(noDockerRes.getOutput().contains("Docker was not detected"));

        stubDockerAvailable(true);
        buildContainerService.setContainerSessionFactory(key -> {
            throw new IllegalStateException("client version 1.32 is too old. Minimum supported API version is 1.44");
        });
        BuildResult sessionErrorRes = buildToolService.compile();
        assertFalse(sessionErrorRes.isSuccess());
        assertEquals(-1, sessionErrorRes.getExitCode());
        assertTrue(sessionErrorRes.getErrors().stream()
                .anyMatch(err -> err.contains("Container execution failed: client version 1.32 is too old")));

        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        AtomicBoolean throwInterrupted = new AtomicBoolean(true);
        buildContainerService.setContainerSessionFactory(key -> {
            sessionClosed.set(false);
            ContainerSession delegate = createMockSession(0, "", null, () -> sessionClosed.set(true));
            return new ContainerSession() {
                @Override
                public ContainerExecResult execInContainer(String... command) throws Exception {
                    if (throwInterrupted.get()) {
                        throw new InterruptedException("Simulated interrupt");
                    }
                    throw new CancellationException("Simulated cancellation");
                }

                @Override
                public boolean isRunning() {
                    return delegate.isRunning();
                }

                @Override
                public void close() {
                    delegate.close();
                }
            };
        });

        try {
            assertThrows(CancellationException.class, () -> buildToolService.compile());
            assertTrue(sessionClosed.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        throwInterrupted.set(false);
        assertThrows(CancellationException.class, () -> buildToolService.compile());
        assertTrue(sessionClosed.get());
    }

    @Test
    void testWarmContainerSessionReusedAndStoppedOnConfigOrProjectChange() throws IOException {
        stubDockerAvailable(true);
        AtomicInteger sessionsCreated = new AtomicInteger(0);
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        List<String[]> executedCommands = new ArrayList<>();
        List<ContainerSessionKey> sessionKeys = new ArrayList<>();

        buildContainerService.setContainerSessionFactory(key -> {
            sessionsCreated.incrementAndGet();
            sessionKeys.add(key);
            sessionClosed.set(false);
            return createMockSession(0, "Container build OK", executedCommands, () -> sessionClosed.set(true));
        });

        String toml = """
                [commands]
                compile = './mvnw test-compile'
                test_all = './mvnw test'
                [env]
                ROXY_CODE = 'true'
                [container]
                enabled = true
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """;
        writeBuildToml(toml);

        BuildResult compileRes = buildToolService.compile();
        assertTrue(compileRes.isSuccess());
        assertEquals("Container build OK", compileRes.getOutput());
        assertEquals(1, sessionsCreated.get());
        assertEquals("eclipse-temurin:25-jdk", sessionKeys.get(0).image());
        assertEquals("/workspace", sessionKeys.get(0).workdir());
        assertEquals("true", sessionKeys.get(0).env().get("ROXY_CODE"));
        assertArrayEquals(new String[]{"sh", "-c", "./mvnw test-compile"}, executedCommands.get(0));

        assertTrue(buildToolService.runTests().isSuccess());
        assertEquals(1, sessionsCreated.get());
        assertArrayEquals(new String[]{"sh", "-c", "./mvnw test"}, executedCommands.get(1));

        buildToolService.saveConfigContent(toml);
        assertTrue(sessionClosed.get());

        buildToolService.compile();
        assertEquals(2, sessionsCreated.get());
        assertFalse(sessionClosed.get());

        buildToolService.handleProjectChanged(new ProjectChangedEvent(projectRoot));
        assertTrue(sessionClosed.get());

        buildToolService.compile();
        assertFalse(sessionClosed.get());
        AtomicBoolean stoppedOnEdt = new AtomicBoolean(true);
        buildContainerService.setContainerSessionFactory(key -> createMockSession(0, "OK", null, () -> {
            stoppedOnEdt.set(SwingUtilities.isEventDispatchThread());
            sessionClosed.set(true);
        }));
        buildToolService.stopActiveContainer();
        sessionClosed.set(false);
        buildToolService.compile();

        try {
            SwingUtilities
                    .invokeAndWait(() -> buildToolService.handleProjectChanged(new ProjectChangedEvent(projectRoot)));
            for (int i = 0; i < 50 && !sessionClosed.get(); i++) {
                Thread.sleep(20);
            }
        } catch (Exception e) {
            fail(e);
        }
        assertTrue(sessionClosed.get());
        assertFalse(stoppedOnEdt.get(), "Container stop must be offloaded off EDT");
    }

    @Test
    void testMalformedTomlValuesHandledWithoutClassCastException() throws IOException {
        writeBuildToml("""
                unknown_top_level = "ignored"
                [commands]
                compile = 'echo hi'
                unknown_build_key = 123
                [env]
                NUMBER_VAL = 42
                BOOL_VAL = true
                [parse]
                error_regex = 12345
                """);
        assertTrue(assertDoesNotThrow(() -> buildToolService.compile()).isSuccess());

        writeBuildToml("parse = 'not-a-section'\n[commands]\ncompile = 'echo hi'\n");
        BuildResult malformedSectionResult = assertDoesNotThrow(() -> buildToolService.compile());
        assertFalse(malformedSectionResult.isSuccess());
        assertEquals(-1, malformedSectionResult.getExitCode());
    }

    @Test
    void testCompileRunTestsAndBuildAndTestAutomaticallyUpdatePlanVerificationStepsAndAllowRequestReview()
            throws IOException {
        EnvironmentService envService = mock(EnvironmentService.class);
        when(envService.getCurrentUser()).thenReturn("dev");
        PlanService realPlanService = new PlanService(envService, eventPublisher, projectService);
        GenericBuildToolService integratedBuildService = new GenericBuildToolService(projectService, new TomlMapper(),
                buildContainerService, eventPublisher, realPlanService);

        realPlanService.submitPlan("Feature", "Goal", new String[]{"Req"}, new String[]{"Implement feature"});
        realPlanService.requestDevelopment("Start coding");
        realPlanService.approveTransition();
        assertEquals(WorkflowPhase.CODE, realPlanService.getCurrentPhase());

        writeBuildToml("""
                [commands]
                compile = 'echo compile-ok'
                test_all = 'echo tests-ok'
                lint = 'echo lint-ok'
                """);

        // 1. compile() marks STEP_COMPILE = true
        assertTrue(integratedBuildService.compile().isSuccess());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        // Failing compile marks STEP_COMPILE = false
        writeBuildToml("""
                [commands]
                compile = 'exit 1'
                test_all = 'echo tests-ok'
                """);
        assertFalse(integratedBuildService.compile().isSuccess());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());

        // 2. Passing runTests() marks both STEP_UNIT_TESTS = true and STEP_COMPILE = true
        assertTrue(integratedBuildService.runTests().isSuccess());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        // Failing runTests() marks STEP_UNIT_TESTS = false while leaving STEP_COMPILE = true
        writeBuildToml("""
                [commands]
                compile = 'echo compile-ok'
                test_all = 'exit 1'
                """);
        assertFalse(integratedBuildService.runTests().isSuccess());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        // 3. File edit resets both verification steps; buildAndTest() + markStepCompleted(0) + requestReview() succeeds
        realPlanService.markStepCompleted(0);
        realPlanService.onFileChanged(new FileChangedEvent("src/Feature.java", FileChangedEvent.ChangeType.MODIFIED));
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        writeBuildToml("""
                [commands]
                compile = 'echo compile-ok'
                test_all = 'echo tests-ok'
                lint = 'echo lint-ok'
                """);
        assertTrue(integratedBuildService.buildAndTest().isSuccess());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        String reviewTransition = realPlanService.requestReview("All steps and buildAndTest verified");
        assertTrue(reviewTransition.contains("Autonomous transition from CODE to REVIEW"));
        assertEquals(WorkflowPhase.REVIEW, realPlanService.getCurrentPhase());
    }
}
