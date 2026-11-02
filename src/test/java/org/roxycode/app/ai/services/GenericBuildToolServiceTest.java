package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSession;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SystemToolService;
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
    void testEnsureConfigAutoDetectionAndTemplateLoading() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");
        String mavenContent = buildToolService.getConfigContent();
        Path configPath = projectDataDir.resolve("build.toml");
        assertTrue(Files.exists(configPath));
        assertContainsAll(mavenContent, "[commands]", "./mvnw test-compile", "[sandbox]", "~/.m2");

        Files.deleteIfExists(configPath);
        Files.deleteIfExists(projectRoot.resolve("pom.xml"));
        Files.writeString(projectRoot.resolve("Cargo.toml"), "[package]\nname = 'demo'\n");
        assertEquals("rust", buildToolService.detectTemplate());
        assertContainsAll(buildToolService.getConfigContent(), "cargo check --tests", "rustfmt {file}");
        assertEquals(8, buildToolService.getAvailableTemplates().size());
        assertContainsAll(buildToolService.loadTemplateContent("default"), "[commands]", "compile = ");
    }

    @Test
    void testNoOpForBlankCommandsFallbacksFilePlaceholdersAndCodePhaseEnforcement() throws IOException {
        writeBuildToml("[commands]\ncompile = ''\nformat_single = ''\n");
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            BuildResult compileNoOp = buildToolService.compile();
            assertTrue(compileNoOp.isSuccess());
            assertEquals("Command 'commands.compile' is not configured (no-op).", compileNoOp.getOutput());
            assertEquals("Command 'commands.format_single' is not configured (no-op).",
                    buildToolService.format("src/App.java").getOutput());
            runner.verifyNoInteractions();
            verifyNoInteractions(eventPublisher);
        }

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

        assertTrue(buildToolService.build().getOutput().contains("fallback-compile"));
        assertTrue(buildToolService.lint("./src/Main.java").getOutput().contains("fallback-lint"));
        assertTrue(buildToolService.format("./src/Main.java").getOutput().contains("fallback-format"));
        verify(eventPublisher).publishEvent(new FileChangedEvent("src/Main.java"));

        assertTrue(buildToolService.install().getOutput().contains("installed-deps"));
        verify(eventPublisher).publishEvent(new FileChangedEvent(""));

        writeBuildToml("""
                [commands]
                build = 'echo explicit-build'
                lint_single = 'echo linting-{file}'
                format_single = 'echo formatting-{file}'
                """);
        assertTrue(buildToolService.build().getOutput().contains("explicit-build"));
        assertTrue(buildToolService.lint("src/dir/Foo.java").getOutput().contains("linting-src/dir/Foo.java"));
        assertTrue(buildToolService.format("src/dir/Foo.java").getOutput().contains("formatting-src/dir/Foo.java"));

        assertFalse(buildToolService.lint("../outside.java").isSuccess());
        assertFalse(buildToolService.format("   ").isSuccess());

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        assertThrows(IllegalStateException.class, () -> buildToolService.install());
        assertThrows(IllegalStateException.class, () -> buildToolService.format());
        assertThrows(IllegalStateException.class, () -> buildToolService.format("src/Main.java"));
        assertDoesNotThrow(() -> buildToolService.lint("src/Main.java"));
    }

    @Test
    void testBuildAndTestPipelinePlaceholdersAndLogTail() throws IOException {
        writeBuildToml(
                "[commands]\ncompile = 'echo step-compile'\ntest_all = 'echo step-test'\nlint = 'echo step-lint'\n");
        BuildResult allPass = buildToolService.buildAndTest();
        assertTrue(allPass.isSuccess());
        assertContainsAll(allPass.getOutput(), "COMPILE OUTPUT:", "step-compile", "TEST OUTPUT:", "step-test",
                "LINT OUTPUT:", "step-lint");

        writeBuildToml("[commands]\ncompile = 'echo step-compile'\ntest_all = 'exit 2'\nlint = 'echo no-lint'\n");
        BuildResult testFail = buildToolService.buildAndTest();
        assertFalse(testFail.isSuccess());
        assertEquals(2, testFail.getExitCode());
        assertFalse(testFail.getOutput().contains("no-lint"));

        writeBuildToml("[commands]\ntest_single = 'echo {testName}'\n");
        assertTrue(buildToolService.runSingleTest("MyTest").getOutput().contains("MyTest"));
        assertTrue(Files.readString(projectDataDir.resolve("tmp/build.log")).contains("MyTest"));

        String truncated = GenericBuildToolService.getLogTail("HEADER-" + "A".repeat(5000) + "-TAIL");
        assertTrue(truncated.startsWith(GenericBuildToolService.LOG_TRUNCATED_PREFIX));
        assertTrue(truncated.endsWith("-TAIL"));
        assertEquals("", GenericBuildToolService.getLogTail(null));
    }

    @Test
    void testContainerAndNativeSandboxExecutionAndSessionLifecycle() throws IOException {
        writeBuildToml("[commands]\ncompile = './mvnw test-compile'\n[sandbox]\nmode = 'native'\n");
        when(systemToolService.checkNativeSandboxAvailable()).thenReturn(false);
        assertTrue(buildToolService.compile().getOutput().contains("Native OS sandbox is not available"));

        when(systemToolService.checkNativeSandboxAvailable()).thenReturn(true);
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(projectRoot), anyMap(), any(), eq(true), any()))
                    .thenReturn(new ProcessResult(0, "native-sandbox-ok", "", false));
            assertEquals("native-sandbox-ok", buildToolService.compile().getOutput());
        }

        String dockerToml = """
                [commands]
                compile = './mvnw test-compile'
                test_all = './mvnw test'
                [parse]
                error_regex = '^.[E]RROR.'
                test_failure_regex = '^FAIL:'
                [sandbox]
                mode = 'docker'
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """;
        writeBuildToml(dockerToml);

        stubDockerAvailable(false);
        assertTrue(buildToolService.compile().getOutput().contains("Docker is not available"));

        stubDockerAvailable(true);
        AtomicInteger sessionsCreated = new AtomicInteger(0);
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        buildContainerService.setContainerSessionFactory(key -> {
            sessionsCreated.incrementAndGet();
            sessionClosed.set(false);
            return createMockSession(1, "[ERROR] Compile err\nFAIL: MyTest#fail", null, () -> sessionClosed.set(true));
        });

        Path persistedLog = projectDataDir.resolve("tmp/build.log");
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), isNull(), eq(false)))
                    .thenReturn(new ProcessResult(0, "[ERROR] Compile err\n", "", false))
                    .thenReturn(new ProcessResult(0, "FAIL: MyTest#fail\n", "", false));

            BuildResult result = buildToolService.runTests();
            assertFalse(result.isSuccess());
            assertEquals(List.of("[ERROR] Compile err"), result.getErrors());
            assertEquals(List.of("FAIL: MyTest#fail"), result.getFailedTests());
        }
        assertTrue(persistedLog.toFile().exists());

        buildToolService.compile();
        assertEquals(1, sessionsCreated.get());
        buildToolService.saveConfigContent(dockerToml);
        assertTrue(sessionClosed.get());

        sessionClosed.set(false);
        AtomicBoolean stoppedOnEdt = new AtomicBoolean(true);
        buildContainerService.setContainerSessionFactory(key -> createMockSession(0, "OK", null, () -> {
            stoppedOnEdt.set(SwingUtilities.isEventDispatchThread());
            sessionClosed.set(true);
        }));
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
        assertFalse(stoppedOnEdt.get());

        buildContainerService.setContainerSessionFactory(key -> new ContainerSession() {
            @Override
            public ContainerExecResult execInContainer(String... command) throws Exception {
                throw new InterruptedException("Interrupted");
            }

            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void close() {
                sessionClosed.set(true);
            }
        });
        try {
            assertThrows(CancellationException.class, () -> buildToolService.compile());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testCompileRunTestsAndBuildAndTestAutomaticallyUpdatePlanVerificationSteps() throws IOException {
        EnvironmentService envService = mock(EnvironmentService.class);
        when(envService.getCurrentUser()).thenReturn("dev");
        PlanService realPlanService = new PlanService(envService, eventPublisher, projectService);
        GenericBuildToolService integratedBuildService = new GenericBuildToolService(projectService, new TomlMapper(),
                buildContainerService, eventPublisher, realPlanService);

        realPlanService.submitPlan("Feature", "Goal", new String[]{"Req"}, new String[]{"Implement feature"});
        realPlanService.requestDevelopment("Start coding");
        realPlanService.approveTransition();

        writeBuildToml("[commands]\ncompile = 'echo ok'\ntest_all = 'echo ok'\nlint = 'echo ok'\n");
        assertTrue(integratedBuildService.compile().isSuccess());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        assertTrue(integratedBuildService.runTests().isSuccess());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        realPlanService.markStepCompleted(0);
        realPlanService.onFileChanged(new FileChangedEvent("src/Feature.java", FileChangedEvent.ChangeType.MODIFIED));
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        assertTrue(integratedBuildService.buildAndTest().isSuccess());
        assertTrue(realPlanService.requestReview("All steps verified").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, realPlanService.getCurrentPhase());
    }

    @Test
    void testSanitizeTomlEscapesAndSaveValidation() throws IOException {
        String bs = String.valueOf((char) 92);
        String dq = String.valueOf((char) 34);
        String nl = String.valueOf((char) 10);
        String rawTomlWithUnescapedDoubleQuotedRegex = "[commands]" + nl + "compile = " + dq + "./mvnw test-compile"
                + dq + nl + "lint_single = " + dq + "./mvnw pmd:check spotless:check -DspotlessFiles=.*" + bs
                + "Q{file}" + bs + "E$" + dq + nl + "format_single = " + dq + "./mvnw spotless:apply -DspotlessFiles=.*"
                + bs + "Q{file}" + bs + "E$" + dq + nl + nl + "[parse]" + nl + "error_regex = " + dq + bs + "[ERROR"
                + bs + "].*" + bs + "[" + bs + "d+," + bs + "d+" + bs + "]|PMD Failure:" + dq + nl;
        writeBuildToml(rawTomlWithUnescapedDoubleQuotedRegex);

        GenericBuildToolService.BuildConfig loaded = buildToolService.loadConfig();
        assertEquals("./mvnw test-compile", loaded.commands().compile());
        assertEquals("./mvnw pmd:check spotless:check -DspotlessFiles=.*" + bs + "Q{file}" + bs + "E$",
                loaded.commands().lintSingle());
        assertEquals("./mvnw spotless:apply -DspotlessFiles=.*" + bs + "Q{file}" + bs + "E$",
                loaded.commands().formatSingle());
        assertEquals(bs + "[ERROR" + bs + "].*" + bs + "[" + bs + "d+," + bs + "d+" + bs + "]|PMD Failure:",
                loaded.parse().errorRegex());

        assertDoesNotThrow(() -> buildToolService.saveConfigContent(rawTomlWithUnescapedDoubleQuotedRegex));

        Path configPath = projectDataDir.resolve("build.toml");
        String beforeInvalidSave = Files.readString(configPath);
        assertThrows(IOException.class,
                () -> buildToolService.saveConfigContent("[commands" + nl + "compile = " + dq + "unclosed"));
        assertEquals(beforeInvalidSave, Files.readString(configPath));
    }
}
