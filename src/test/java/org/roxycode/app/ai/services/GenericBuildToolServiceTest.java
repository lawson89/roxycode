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
            String trimmed = arg.trim().replace('\\', '/');
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
        assertContainsAll(buildToolService.getConfigContent(), "cargo check --tests", "cargo fmt");
        assertEquals(8, buildToolService.getAvailableTemplates().size());
        assertContainsAll(buildToolService.loadTemplateContent("default"), "[commands]", "compile = ");
    }

    @Test
    void testNoOpForBlankCommandsFallbacksAndCodePhaseEnforcement() throws IOException {
        writeBuildToml("[commands]\ncompile = ''\nformat = ''\n");
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            BuildResult compileNoOp = buildToolService.compile();
            assertTrue(compileNoOp.isSuccess());
            assertEquals("Command 'commands.compile' is not configured (no-op).", compileNoOp.getOutput());
            assertEquals("Command 'commands.format' is not configured (no-op).", buildToolService.format().getOutput());
            runner.verifyNoInteractions();
            verifyNoInteractions(eventPublisher);
        }

        writeBuildToml("""
                [commands]
                install = 'echo installed-deps'
                compile = 'echo fallback-compile'
                build = ''
                lint = 'echo project-lint'
                format = 'echo project-format'
                """);

        assertTrue(buildToolService.build().getOutput().contains("fallback-compile"));
        assertTrue(buildToolService.lint().getOutput().contains("project-lint"));
        assertTrue(buildToolService.format().getOutput().contains("project-format"));
        assertTrue(buildToolService.install().getOutput().contains("installed-deps"));
        verify(eventPublisher, times(2)).publishEvent(new FileChangedEvent(""));

        writeBuildToml("[commands]\nbuild = 'echo explicit-build'\n");
        assertTrue(buildToolService.build().getOutput().contains("explicit-build"));

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        assertThrows(IllegalStateException.class, () -> buildToolService.install());
        assertThrows(IllegalStateException.class, () -> buildToolService.format());
        assertDoesNotThrow(() -> buildToolService.lint());
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

        // Lint failure during runTests() or buildAndTest() or standalone lint() marks STEP_UNIT_TESTS false and blocks
        // review
        writeBuildToml("""
                [commands]
                compile = 'echo ok'
                test_all = 'echo tests-passed'
                lint = 'echo pmd-violation && exit 1'
                """);
        BuildResult runTestsLintFail = integratedBuildService.runTests();
        assertFalse(runTestsLintFail.isSuccess());
        assertContainsAll(runTestsLintFail.getOutput(), "TEST OUTPUT:", "tests-passed", "LINT OUTPUT:",
                "pmd-violation");
        assertTrue(realPlanService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());
        assertThrows(IllegalStateException.class, () -> realPlanService.requestReview("Should be blocked by lint"));

        realPlanService.updateVerificationStep("Unit tests pass", true);
        assertFalse(integratedBuildService.lint().isSuccess());
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        realPlanService.updateVerificationStep("Unit tests pass", true);
        BuildResult buildAndTestLintFail = integratedBuildService.buildAndTest();
        assertFalse(buildAndTestLintFail.isSuccess());
        assertContainsAll(buildAndTestLintFail.getOutput(), "COMPILE OUTPUT:", "TEST OUTPUT:", "LINT OUTPUT:",
                "pmd-violation");
        assertFalse(realPlanService.getCurrentPlan().technicalSteps().get(2).completed());

        writeBuildToml("""
                [commands]
                compile = 'echo ok'
                test_all = 'echo ok'
                lint = 'echo ok'
                """);
        assertTrue(integratedBuildService.buildAndTest().isSuccess());
        assertTrue(realPlanService.requestReview("All steps verified").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, realPlanService.getCurrentPhase());
    }

    @Test
    void testSanitizeTomlEscapesAndSaveValidation() throws IOException {
        String bs = "\\";
        String dq = "\"";
        String nl = "\n";
        String rawTomlWithUnescapedDoubleQuotedRegex = "[commands]" + nl + "compile = " + dq + "./mvnw test-compile"
                + dq + nl + "lint = " + dq + "./mvnw pmd:check spotless:check -DspotlessFiles=.*" + bs + "QApp.java"
                + bs + "E$" + dq + nl + nl + "[parse]" + nl + "error_regex = " + dq + bs + "[ERROR" + bs + "].*" + bs
                + "[" + bs + "d+," + bs + "d+" + bs + "]|PMD Failure:" + dq + nl;
        writeBuildToml(rawTomlWithUnescapedDoubleQuotedRegex);

        GenericBuildToolService.BuildConfig loaded = buildToolService.loadConfig();
        assertEquals("./mvnw test-compile", loaded.commands().compile());
        assertEquals("./mvnw pmd:check spotless:check -DspotlessFiles=.*" + bs + "QApp.java" + bs + "E$",
                loaded.commands().lint());
        assertEquals(bs + "[ERROR" + bs + "].*" + bs + "[" + bs + "d+," + bs + "d+" + bs + "]|PMD Failure:",
                loaded.parse().errorRegex());

        assertDoesNotThrow(() -> buildToolService.saveConfigContent(rawTomlWithUnescapedDoubleQuotedRegex));

        Path configPath = projectDataDir.resolve("build.toml");
        String beforeInvalidSave = Files.readString(configPath);
        assertThrows(IOException.class,
                () -> buildToolService.saveConfigContent("[commands" + nl + "compile = " + dq + "unclosed"));
        assertEquals(beforeInvalidSave, Files.readString(configPath));
    }

    @Test
    void testAnsiStrippingMultiLineRipGrepBlocksAndJunitXmlExtraction() throws IOException {
        String esc = "\u001b";
        String nl = "\n";
        String bs = "\\";
        String ansiLog = esc + "[1;31m[ERROR] /workspace/src/Foo.java:[42,15] cannot find symbol" + esc + "[0m" + nl
                + "  symbol:   method bar()" + nl + "  location: class com.example.Foo" + nl + "[INFO] 1 error" + nl
                + "[INFO] Building next module" + nl + "[INFO] Checking rust crate..." + nl + esc
                + "[31merror[E0308]: mismatched types" + esc + "[0m" + nl + "  --> src/main.rs:14:9" + nl + "   |" + nl
                + "14 |     foo('hello')" + nl + "   |         ^^^^^^^ expected 'i32', found '&str'" + nl
                + "normal output line" + nl + "--- FAIL: TestAuth (0.01s)" + nl
                + "    auth_test.go:42: expected 200, got 401" + nl;

        String stripped = GenericBuildToolService.stripAnsiCodes(ansiLog);
        assertFalse(stripped.contains(esc));
        assertTrue(stripped.startsWith("[ERROR] /workspace/src/Foo.java:[42,15] cannot find symbol"));

        Path logFile = projectDataDir.resolve("tmp/build.log");
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, stripped);

        String rustAndJavacRegex = "^(" + bs + "[ERROR" + bs + "].*" + bs + "[" + bs + "d+," + bs + "d+" + bs
                + "]|error" + bs + "[E" + bs + "d+" + bs + "]:)";
        List<String> javacAndRustErrors = buildToolService.extractWithRipGrep(rustAndJavacRegex, logFile, 4);
        assertEquals(2, javacAndRustErrors.size());
        assertContainsAll(javacAndRustErrors.get(0), "[ERROR] /workspace/src/Foo.java:[42,15] cannot find symbol",
                "  symbol:   method bar()", "  location: class com.example.Foo");
        assertContainsAll(javacAndRustErrors.get(1), "error[E0308]: mismatched types", "  --> src/main.rs:14:9",
                "   |         ^^^^^^^ expected 'i32', found '&str'");

        List<String> goFailures = buildToolService.extractWithRipGrep("^--- FAIL:", logFile, 1);
        assertEquals(1, goFailures.size());
        assertEquals("--- FAIL: TestAuth (0.01s)" + nl + "    auth_test.go:42: expected 200, got 401",
                goFailures.get(0));

        List<String> singleLineOnly = buildToolService.extractWithRipGrep("^--- FAIL:", logFile, 0);
        assertEquals(List.of("--- FAIL: TestAuth (0.01s)"), singleLineOnly);

        // Verify deduplication and MAX_EXTRACTED_BLOCKS cap in parseRipGrepOutput
        StringBuilder manyBlocks = new StringBuilder();
        manyBlocks.append("ERROR: duplicate").append(nl).append("  detail 1").append(nl).append("--").append(nl)
                .append("ERROR: duplicate").append(nl).append("  detail 1").append(nl);
        for (int i = 0; i < 40; i++) {
            manyBlocks.append("--").append(nl).append("ERROR: unique-").append(i).append(nl).append("  line-").append(i)
                    .append(nl);
        }
        List<String> cappedBlocks = GenericBuildToolService.parseRipGrepOutput(manyBlocks.toString(), 2);
        assertEquals(GenericBuildToolService.MAX_EXTRACTED_BLOCKS, cappedBlocks.size());
        assertEquals("ERROR: duplicate" + nl + "  detail 1", cappedBlocks.get(0));
        assertEquals("ERROR: unique-0" + nl + "  line-0", cappedBlocks.get(1));

        // Verify JUnit XML report extraction (fresh vs stale reports)
        Path surefireDir = Files.createDirectories(projectRoot.resolve("target/surefire-reports"));
        Path staleReport = surefireDir.resolve("TEST-com.example.StaleTest.xml");
        Files.writeString(staleReport, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.StaleTest" tests="1" failures="1">
                  <testcase classname="com.example.StaleTest" name="oldFailure">
                    <failure type="java.lang.AssertionError" message="stale error">java.lang.AssertionError: stale error
                        at com.example.StaleTest.oldFailure(StaleTest.java:10)</failure>
                  </testcase>
                </testsuite>
                """);
        Files.setLastModifiedTime(staleReport,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000L));

        Path freshReport = surefireDir.resolve("TEST-com.example.FreshTest.xml");
        Files.writeString(freshReport,
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <testsuite name="com.example.FreshTest" tests="2" failures="1" errors="1">
                          <testcase classname="com.example.FreshTest" name="testAssertion">
                            <failure type="org.opentest4j.AssertionFailedError" message="expected: &lt;200&gt; but was: &lt;401&gt;">org.opentest4j.AssertionFailedError: expected: &lt;200&gt; but was: &lt;401&gt;
                                at com.example.FreshTest.testAssertion(FreshTest.java:25)
                                at java.base/java.lang.reflect.Method.invoke(Method.java:580)</failure>
                          </testcase>
                          <testcase classname="com.example.FreshTest" name="testUnhandledError">
                            <error type="java.lang.NullPointerException" message="Cannot invoke method on null">java.lang.NullPointerException: Cannot invoke method on null
                                at com.example.FreshTest.testUnhandledError(FreshTest.java:39)</error>
                          </testcase>
                        </testsuite>
                        """);

        writeBuildToml("[commands]" + nl + "test_all = 'printf \"" + bs + "033[31mFAIL: console failure" + bs + "033[0m"
                + bs + "n  at ConsoleTest.java:12" + bs + "n\" && exit 1'" + nl + "[parse]" + nl
                + "test_failure_regex = '^FAIL:'" + nl + "context_lines = 2" + nl + "junit_xml_paths = ["
                + "\"target/surefire-reports\"]" + nl);

        BuildResult testResult = buildToolService.runTests();
        assertFalse(testResult.isSuccess());
        assertFalse(testResult.getOutput().contains(esc));
        assertEquals(3, testResult.getFailedTests().size());
        assertEquals("FAIL: console failure" + nl + "  at ConsoleTest.java:12", testResult.getFailedTests().get(0));
        assertContainsAll(testResult.getFailedTests().get(1),
                "com.example.FreshTest#testAssertion [FAILURE]: org.opentest4j.AssertionFailedError: expected: <200> but was: <401>",
                "  at com.example.FreshTest.testAssertion(FreshTest.java:25)");
        assertContainsAll(testResult.getFailedTests().get(2),
                "com.example.FreshTest#testUnhandledError [ERROR]: java.lang.NullPointerException: Cannot invoke method on null",
                "  at com.example.FreshTest.testUnhandledError(FreshTest.java:39)");
        for (String failure : testResult.getFailedTests()) {
            assertFalse(failure.contains("StaleTest"));
        }
    }
}
