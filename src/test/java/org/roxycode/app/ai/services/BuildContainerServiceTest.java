package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.command.CreateContainerCmd;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.roxycode.app.ai.services.BuildContainerService.ContainerConfig;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSession;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSessionKey;
import org.roxycode.app.service.SystemToolService;
import org.testcontainers.containers.GenericContainer;

class BuildContainerServiceTest {

    @TempDir Path tempDir;

    private SystemToolService systemToolService;
    private BuildContainerService buildContainerService;

    @BeforeEach
    void setUp() {
        systemToolService = mock(SystemToolService.class);
        buildContainerService = new BuildContainerService(systemToolService);
    }

    @Test
    void testContainerNameGenerationAndSanitization() {
        Path normalProject = Path.of("/Users/dev/My Cool Project!");
        assertEquals("my-cool-project", BuildContainerService.sanitizeProjectName(normalProject));

        String containerName = BuildContainerService.buildContainerName(normalProject);
        assertTrue(
                containerName.matches("^roxycode-build-my-cool-project-[a-f0-9]{8}$"),
                "Unexpected container name: " + containerName);

        String secondName = BuildContainerService.buildContainerName(normalProject);
        assertNotEquals(
                containerName,
                secondName,
                "Container names should have a unique suffix to avoid collisions");

        assertEquals("project", BuildContainerService.sanitizeProjectName(Path.of("/")));
        assertEquals("project", BuildContainerService.sanitizeProjectName(null));
        assertEquals(
                "project", BuildContainerService.sanitizeProjectName(Path.of("/tmp/---___---")));
    }

    @Test
    void testCreateConfiguredContainerSetsIdentifiableNameAndLabels() {
        Path projectPath = tempDir.resolve("Roxy_Test_App");
        ContainerSessionKey key =
                new ContainerSessionKey(
                        projectPath,
                        "eclipse-temurin:25-jdk",
                        "/workspace",
                        Map.of("ROXY_CODE", "true"));

        GenericContainer<?> container = BuildContainerService.createConfiguredContainer(key);

        assertEquals("/workspace", container.getWorkingDirectory());
        assertEquals("true", container.getEnvMap().get("ROXY_CODE"));
        assertEquals("roxycode", container.getLabels().get("app"));
        assertEquals("roxy-test-app", container.getLabels().get("roxycode.project"));

        assertFalse(container.getCreateContainerCmdModifiers().isEmpty());
        CreateContainerCmd mockCmd = mock(CreateContainerCmd.class);
        for (var modifier : container.getCreateContainerCmdModifiers()) {
            modifier.modify(mockCmd);
        }

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockCmd).withName(nameCaptor.capture());
        assertTrue(
                nameCaptor.getValue().matches("^roxycode-build-roxy-test-app-[a-f0-9]{8}$"),
                "Expected roxycode-build-roxy-test-app-<hex>, got: " + nameCaptor.getValue());
    }

    @Test
    void testParseContainerConfigDefaultsAndCustomValues() {
        ContainerConfig defaultConfig = buildContainerService.parseContainerConfig(Map.of());
        assertFalse(defaultConfig.enabled());
        assertEquals("ubuntu:24.04", defaultConfig.image());
        assertEquals("/workspace", defaultConfig.workdir());

        ContainerConfig customConfig =
                buildContainerService.parseContainerConfig(
                        Map.of(
                                "container",
                                Map.of(
                                        "enabled", true,
                                        "image", "eclipse-temurin:25-jdk",
                                        "workdir", "/app")));
        assertTrue(customConfig.enabled());
        assertEquals("eclipse-temurin:25-jdk", customConfig.image());
        assertEquals("/app", customConfig.workdir());
    }

    @Test
    void testIsDockerAvailableDelegatesToSystemToolService() {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(true));
        assertTrue(buildContainerService.isDockerAvailable());

        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));
        assertFalse(buildContainerService.isDockerAvailable());
    }

    @Test
    void testWarmSessionLifecycleReuseAndStop() throws Exception {
        AtomicInteger sessionsCreated = new AtomicInteger(0);
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        List<String[]> commands = new ArrayList<>();

        buildContainerService.setContainerSessionFactory(
                key -> {
                    sessionsCreated.incrementAndGet();
                    sessionClosed.set(false);
                    return new ContainerSession() {
                        @Override
                        public ContainerExecResult execInContainer(String... command) {
                            commands.add(command);
                            return new ContainerExecResult(0, "out", "err");
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

        ContainerConfig config = new ContainerConfig(true, "eclipse-temurin:25-jdk", "/workspace");
        ContainerExecResult res1 =
                buildContainerService.executeCommand(
                        tempDir, config, Map.of("A", "1"), "./mvnw compile");
        assertEquals(0, res1.exitCode());
        assertEquals(
                """
                out
                err""",
                res1.combinedOutput());

        // Same key reuses session
        buildContainerService.executeCommand(tempDir, config, Map.of("A", "1"), "./mvnw test");
        assertEquals(1, sessionsCreated.get());
        assertEquals(2, commands.size());

        // Different env or image recreates session
        buildContainerService.executeCommand(tempDir, config, Map.of("A", "2"), "./mvnw test");
        assertEquals(2, sessionsCreated.get());

        // Explicit stop closes session
        buildContainerService.stopActiveContainer();
        assertTrue(sessionClosed.get());
    }

    @Test
    void testExecuteCommandPropagatesTestcontainersFailureWithoutFallback() {
        buildContainerService.setContainerSessionFactory(
                key -> {
                    throw new IllegalStateException(
                            "client version 1.32 is too old. Minimum supported API version is 1.44");
                });

        ContainerConfig config = new ContainerConfig(true, "eclipse-temurin:25-jdk", "/workspace");
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                buildContainerService.executeCommand(
                                        tempDir, config, Map.of(), "./mvnw compile"));
        assertTrue(thrown.getMessage().contains("client version 1.32 is too old"));
    }

    @Test
    void testIsAndUpdateContainerEnabledInToml() {
        assertFalse(BuildContainerService.isContainerEnabledInToml(null));
        assertFalse(BuildContainerService.isContainerEnabledInToml(""));

        String tomlWithDisabled =
                """
                [build]
                compile = "./mvnw compile"

                [container]
                enabled = false # inline comment
                image = "eclipse-temurin:25-jdk"
                """;
        assertFalse(BuildContainerService.isContainerEnabledInToml(tomlWithDisabled));

        String enabledToml =
                BuildContainerService.updateContainerEnabledInToml(tomlWithDisabled, true);
        assertTrue(BuildContainerService.isContainerEnabledInToml(enabledToml));
        assertTrue(enabledToml.contains("enabled = true # inline comment"));

        String disabledAgain =
                BuildContainerService.updateContainerEnabledInToml(enabledToml, false);
        assertFalse(BuildContainerService.isContainerEnabledInToml(disabledAgain));

        String tomlWithoutSection =
                """
                [build]
                compile = "./mvnw compile"
                """;
        String appended =
                BuildContainerService.updateContainerEnabledInToml(tomlWithoutSection, true);
        assertTrue(appended.contains("[container]"));
        assertTrue(BuildContainerService.isContainerEnabledInToml(appended));
    }
}
