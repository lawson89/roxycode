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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSession;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSessionKey;
import org.roxycode.app.events.ProjectChangedEvent;
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
                if (captured != null)
                    captured.add(command);
                return new ContainerExecResult(exitCode, stdout, "");
            }

            @Override
            public boolean isRunning() {
                return !closed.get();
            }

            @Override
            public void close() {
                closed.set(true);
                if (onClose != null)
                    onClose.run();
            }
        };
    }

    @Test
    void testEnsureConfigAutoDetectionMavenIncludesContainerSection() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");
        buildToolService.compile();

        Path configPath = projectDataDir.resolve("build.toml");
        assertTrue(Files.exists(configPath));
        assertFalse(Files.exists(projectRoot.resolve(".roxycode")));
        assertContainsAll(Files.readString(configPath), "compile", "[container]", "eclipse-temurin:25-jdk");
    }

    @Test
    void testCommandPlaceholders() throws IOException {
        writeBuildToml("[test]\nrun_single = 'echo {testName}'\n");
        assertTrue(buildToolService.runSingleTest("MyTest").getLog().contains("MyTest"));
    }

    @Test
    void testRegexParsing() throws IOException {
        writeBuildToml("""
                [build]
                compile = 'echo [ERROR] Compilation failed'
                [test]
                run_all = 'echo FAIL: MyTest#broken'
                [parse]
                error_regex = '(?m)^.[E]RROR. (.*)$'
                test_failure_regex = '(?m)^FAIL: (.*)$'
                """);

        BuildResult compileResult = buildToolService.compile();
        assertFalse(compileResult.isSuccess());
        assertEquals(List.of("Compilation failed"), compileResult.getErrors());

        BuildResult testResult = buildToolService.runTests();
        assertFalse(testResult.isSuccess());
        assertEquals(0, testResult.getExitCode());
        assertTrue(testResult.getErrors().isEmpty());
        assertEquals(List.of("MyTest#broken"), testResult.getFailedTests());
    }

    @Test
    void testContainerExecutionRegexParsing() throws IOException {
        stubDockerAvailable(true);
        buildContainerService.setContainerSessionFactory(
                key -> createMockSession(1, "[ERROR] Container compile failure\nFAIL: ContainerTest#fail", null, null));

        writeBuildToml("""
                [test]
                run_all = './mvnw test'
                [parse]
                error_regex = '(?m)^.[E]RROR. (.*)$'
                test_failure_regex = '(?m)^FAIL: (.*)$'
                [container]
                enabled = true
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """);

        BuildResult result = buildToolService.runTests();
        assertFalse(result.isSuccess());
        assertEquals(1, result.getExitCode());
        assertEquals(List.of("Container compile failure"), result.getErrors());
        assertEquals(List.of("ContainerTest#fail"), result.getFailedTests());
    }

    @Test
    void testContainerEnabledFailsFastWhenDockerUnavailable() throws IOException {
        stubDockerAvailable(false);
        writeBuildToml(
                "[build]\ncompile = './mvnw compile'\n[container]\nenabled = true\nimage = 'eclipse-temurin:25-jdk'\nworkdir = '/workspace'\n");

        BuildResult result = buildToolService.compile();
        assertFalse(result.isSuccess());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getOutput().contains("Docker was not detected"));
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void testContainerEnabledFailsBuildWhenTestcontainersSessionThrows() throws IOException {
        stubDockerAvailable(true);
        buildContainerService.setContainerSessionFactory(key -> {
            throw new IllegalStateException("client version 1.32 is too old. Minimum supported API version is 1.44");
        });
        writeBuildToml(
                "[build]\ncompile = './mvnw compile'\n[container]\nenabled = true\nimage = 'eclipse-temurin:25-jdk'\nworkdir = '/workspace'\n");

        BuildResult result = buildToolService.compile();
        assertFalse(result.isSuccess());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getOutput().contains("client version 1.32 is too old"));
        assertTrue(result.getErrors().stream()
                .anyMatch(err -> err.contains("Container execution failed: client version 1.32 is too old")));
    }

    @Test
    void testContainerExecutionInterruptedOrCancelledStopsActiveContainerAndThrowsCancellationException()
            throws IOException {
        stubDockerAvailable(true);
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        AtomicBoolean throwInterrupted = new AtomicBoolean(true);

        buildContainerService.setContainerSessionFactory(key -> {
            sessionClosed.set(false);
            return new ContainerSession() {
                @Override
                public ContainerExecResult execInContainer(String... command) throws Exception {
                    if (throwInterrupted.get()) {
                        throw new InterruptedException("Simulated interrupt during docker exec");
                    }
                    throw new java.util.concurrent.CancellationException("Simulated cancellation during docker exec");
                }

                @Override
                public boolean isRunning() {
                    return !sessionClosed.get();
                }

                @Override
                public void close() {
                    sessionClosed.set(true);
                }
            };
        });

        writeBuildToml("""
                [build]
                compile = './mvnw compile'
                [container]
                enabled = true
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """);

        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> buildToolService.compile());
            assertTrue(sessionClosed.get(), "Active container must be stopped on InterruptedException");
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }

        throwInterrupted.set(false);
        assertThrows(java.util.concurrent.CancellationException.class, () -> buildToolService.compile());
        assertTrue(sessionClosed.get(), "Active container must be stopped on CancellationException");
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
    void testGetConfigContentAndCompileFallbackWhenEnsureConfigCannotWriteToDisk() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");
        Files.createDirectories(projectDataDir.getParent());
        Files.writeString(projectDataDir, "not a directory");

        String content = assertDoesNotThrow(() -> buildToolService.getConfigContent());
        assertContainsAll(content, "./mvnw compile", "[container]");
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

    @Test
    void testLoadTemplatesWithIsolatedThreadContextClassLoader() throws IOException {
        Files.writeString(projectRoot.resolve("pyproject.toml"), "[project]");

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader emptyClassLoader = new URLClassLoader(new URL[0], null)) {
            Thread.currentThread().setContextClassLoader(emptyClassLoader);

            String content = buildToolService.getConfigContent();
            assertContainsAll(content, "pytest", "[container]", "python:3.12");
        } finally {
            Thread.currentThread().setContextClassLoader(originalTCCL);
        }
    }
}
