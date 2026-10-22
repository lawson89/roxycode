package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    @TempDir Path tempDir;

    private ProjectService projectService;
    private TomlMapper tomlMapper;
    private SystemToolService systemToolService;
    private BuildContainerService buildContainerService;
    private GenericBuildToolService buildToolService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        tomlMapper = new TomlMapper();
        systemToolService = mock(SystemToolService.class);
        buildContainerService = new BuildContainerService(systemToolService);

        buildToolService =
                new GenericBuildToolService(projectService, tomlMapper, buildContainerService);

        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
    }

    @Test
    void testDetectNoConfig() {
        assertFalse(buildToolService.detect());
    }

    @Test
    void testDetectWithConfig() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(
                tempDir.resolve(".roxycode/build.toml"),
                """
                [build]
                compile = 'echo hi'
                """);
        assertTrue(buildToolService.detect());
    }

    @Test
    void testEnsureConfigAutoDetectionMavenIncludesContainerSection() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        buildToolService.compile();

        Path configPath = tempDir.resolve(".roxycode/build.toml");
        assertTrue(Files.exists(configPath));
        String content = Files.readString(configPath);
        assertTrue(content.contains("compile"));
        assertTrue(content.contains("[container]"));
        assertTrue(content.contains("eclipse-temurin:25-jdk"));
    }

    @Test
    void testCommandPlaceholders() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(
                tempDir.resolve(".roxycode/build.toml"),
                """
                [test]
                run_single = 'echo {testName}'
                """);

        BuildResult result = buildToolService.runSingleTest("MyTest");
        assertTrue(result.getLog().contains("MyTest"));
    }

    @Test
    void testRegexParsing() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(
                tempDir.resolve(".roxycode/build.toml"),
                """
                [build]
                compile = 'echo [ERROR] Compilation failed'
                [parse]
                error_regex = '(?m)^.[E]RROR. (.*)$'
                """);

        BuildResult result = buildToolService.compile();
        assertEquals(1, result.getErrors().size());
        assertEquals("Compilation failed", result.getErrors().get(0));
    }

    @Test
    void testEnvironmentVariables() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(
                tempDir.resolve(".roxycode/build.toml"),
                """
                [build]
                compile = 'echo test'
                [env]
                TEST_VAR = 'hello-env'
                """);

        BuildResult result = buildToolService.compile();
        assertTrue(result.isSuccess());
    }

    @Test
    void testContainerEnabledFailsFastWhenDockerUnavailable() throws IOException {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(
                tempDir.resolve(".roxycode/build.toml"),
                """
                [build]
                compile = './mvnw compile'
                [container]
                enabled = true
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """);

        BuildResult result = buildToolService.compile();
        assertFalse(result.isSuccess());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getOutput().contains("Docker was not detected"));
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void testWarmContainerSessionReusedAndStoppedOnConfigOrProjectChange() throws IOException {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(true));

        AtomicInteger sessionsCreated = new AtomicInteger(0);
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        List<String[]> executedCommands = new ArrayList<>();
        List<ContainerSessionKey> sessionKeys = new ArrayList<>();

        buildContainerService.setContainerSessionFactory(
                key -> {
                    sessionsCreated.incrementAndGet();
                    sessionKeys.add(key);
                    sessionClosed.set(false);
                    return new ContainerSession() {
                        @Override
                        public ContainerExecResult execInContainer(String... command) {
                            executedCommands.add(command);
                            return new ContainerExecResult(0, "Container build OK", "");
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

        Files.createDirectories(tempDir.resolve(".roxycode"));
        String toml =
                """
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
        Files.writeString(tempDir.resolve(".roxycode/build.toml"), toml);

        BuildResult compileRes = buildToolService.compile();
        assertTrue(compileRes.isSuccess());
        assertEquals("Container build OK", compileRes.getOutput());
        assertEquals(1, sessionsCreated.get());
        assertEquals("eclipse-temurin:25-jdk", sessionKeys.get(0).image());
        assertEquals("/workspace", sessionKeys.get(0).workdir());
        assertEquals("true", sessionKeys.get(0).env().get("ROXY_CODE"));
        assertArrayEquals(new String[] {"sh", "-c", "./mvnw compile"}, executedCommands.get(0));

        // Second command reuses warm container session
        BuildResult testRes = buildToolService.runTests();
        assertTrue(testRes.isSuccess());
        assertEquals(1, sessionsCreated.get(), "Warm container session should be reused");
        assertArrayEquals(new String[] {"sh", "-c", "./mvnw test"}, executedCommands.get(1));

        // Saving config stops active container
        buildToolService.saveConfigContent(toml);
        assertTrue(sessionClosed.get(), "Saving config should stop active warm container");

        // Next command starts a new container session
        buildToolService.compile();
        assertEquals(2, sessionsCreated.get());
        assertFalse(sessionClosed.get());

        // Switching project stops active container
        buildToolService.handleProjectChanged(new ProjectChangedEvent(tempDir));
        assertTrue(sessionClosed.get(), "Switching project should stop active warm container");
    }
}
