package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSession;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSessionKey;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SystemToolService;

class GenericBuildToolServiceTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path projectDataDir;
    private ProjectService projectService;
    private SystemToolService systemToolService;
    private BuildContainerService buildContainerService;
    private GenericBuildToolService buildToolService;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = Files.createDirectories(tempDir.resolve("workspace"));
        projectDataDir = tempDir.resolve("roxy-home/projects/workspace-12345678");
        projectService = mock(ProjectService.class);
        systemToolService = mock(SystemToolService.class);
        buildContainerService = new BuildContainerService(systemToolService);
        buildToolService = new GenericBuildToolService(projectService, new TomlMapper(), buildContainerService);

        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        lenient().when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
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
        buildToolService.compile();

        Path configPath = projectDataDir.resolve("build.toml");
        assertTrue(Files.exists(configPath));
        assertFalse(Files.exists(projectRoot.resolve(".roxycode")));
        assertContainsAll(Files.readString(configPath), "compile", "[container]", "eclipse-temurin:25-jdk");

        Files.deleteIfExists(configPath);
        Files.deleteIfExists(projectDataDir.resolve("tmp/build.log"));
        Files.deleteIfExists(projectDataDir.resolve("tmp"));
        Files.deleteIfExists(projectDataDir);
        Files.writeString(projectDataDir, "not a directory");
        String fallbackContent = assertDoesNotThrow(() -> buildToolService.getConfigContent());
        assertContainsAll(fallbackContent, "./mvnw compile", "[container]");

        Files.deleteIfExists(projectDataDir);
        Files.deleteIfExists(projectRoot.resolve("pom.xml"));
        Files.writeString(projectRoot.resolve("pyproject.toml"), "[project]");

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader emptyClassLoader = new URLClassLoader(new URL[0], null)) {
            Thread.currentThread().setContextClassLoader(emptyClassLoader);
            String pyContent = buildToolService.getConfigContent();
            assertContainsAll(pyContent, "pytest", "[container]", "python:3.12");
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }

    @Test
    void testCommandPlaceholdersAndLogTailPersistence() throws IOException {
        writeBuildToml("[test]\nrun_single = 'echo {testName}'\n");
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
                [test]
                run_all = './mvnw test'
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
                [build]
                compile = './mvnw compile'
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
                [build]
                compile = './mvnw compile'
                [test]
                run_all = './mvnw test'
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
        assertArrayEquals(new String[]{"sh", "-c", "./mvnw compile"}, executedCommands.get(0));

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
                [build]
                compile = 'echo hi'
                unknown_build_key = 123
                [env]
                NUMBER_VAL = 42
                BOOL_VAL = true
                [parse]
                error_regex = 12345
                """);
        assertTrue(assertDoesNotThrow(() -> buildToolService.compile()).isSuccess());

        writeBuildToml("parse = 'not-a-section'\n[build]\ncompile = 'echo hi'\n");
        BuildResult malformedSectionResult = assertDoesNotThrow(() -> buildToolService.compile());
        assertFalse(malformedSectionResult.isSuccess());
        assertEquals(-1, malformedSectionResult.getExitCode());
    }
}
