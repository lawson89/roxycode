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
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.ui.SwingTestUtils;
import org.testcontainers.containers.GenericContainer;

class BuildContainerServiceTest {

    @TempDir
    Path tempDir;

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
        assertEquals("my-cool-project", ProjectService.sanitizeProjectName(normalProject));

        String containerName = BuildContainerService.buildContainerName(normalProject);
        assertTrue(containerName.matches("^roxycode-build-my-cool-project-[a-f0-9]{8}$"));
        assertNotEquals(containerName, BuildContainerService.buildContainerName(normalProject));

        assertEquals("project", ProjectService.sanitizeProjectName(Path.of("/")));
        assertEquals("project", ProjectService.sanitizeProjectName(null));
        assertEquals("project", ProjectService.sanitizeProjectName(Path.of("/tmp/---___---")));
    }

    @Test
    void testCreateConfiguredContainerSetsIdentifiableNameAndLabels() {
        ContainerSessionKey key = new ContainerSessionKey(tempDir.resolve("Roxy_Test_App"), "eclipse-temurin:25-jdk",
                "/workspace", Map.of("ROXY_CODE", "true"));

        GenericContainer<?> container = BuildContainerService.createConfiguredContainer(key);
        assertEquals("/workspace", container.getWorkingDirectory());
        assertEquals("true", container.getEnvMap().get("ROXY_CODE"));
        assertEquals("roxycode", container.getLabels().get("app"));
        assertEquals("roxy-test-app", container.getLabels().get("roxycode.project"));
        assertFalse(container.getCreateContainerCmdModifiers().isEmpty());

        CreateContainerCmd mockCmd = mock(CreateContainerCmd.class);
        container.getCreateContainerCmdModifiers().forEach(m -> m.modify(mockCmd));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockCmd).withName(nameCaptor.capture());
        assertTrue(nameCaptor.getValue().matches("^roxycode-build-roxy-test-app-[a-f0-9]{8}$"));
    }

    @Test
    void testParseContainerConfigDefaultsAndCustomValues() {
        for (ContainerConfig cfg : new ContainerConfig[]{null, new ContainerConfig(false, "  ", "")}) {
            ContainerConfig parsed = buildContainerService.parseContainerConfig(cfg);
            assertFalse(parsed.enabled());
            assertEquals("ubuntu:24.04", parsed.image());
            assertEquals("/workspace", parsed.workdir());
        }

        ContainerConfig custom = buildContainerService
                .parseContainerConfig(new ContainerConfig(true, "eclipse-temurin:25-jdk", "/app"));
        assertTrue(custom.enabled());
        assertEquals("eclipse-temurin:25-jdk", custom.image());
        assertEquals("/app", custom.workdir());
    }

    @Test
    void testIsDockerAvailableDelegatesToSystemToolService() {
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(true));
        assertTrue(buildContainerService.isDockerAvailable());

        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));
        assertFalse(buildContainerService.isDockerAvailable());
    }

    @Test
    void testWarmSessionLifecycleReuseAndStop() throws Exception {
        AtomicInteger sessionsCreated = new AtomicInteger(0);
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        List<String[]> commands = new ArrayList<>();

        buildContainerService.setContainerSessionFactory(key -> {
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
        ContainerExecResult res1 = buildContainerService.executeCommand(tempDir, config, Map.of("A", "1"),
                "./mvnw compile");
        assertEquals(0, res1.exitCode());
        assertEquals("out\nerr", res1.combinedOutput());

        buildContainerService.executeCommand(tempDir, config, Map.of("A", "1"), "./mvnw test");
        assertEquals(1, sessionsCreated.get());
        assertEquals(2, commands.size());

        buildContainerService.executeCommand(tempDir, config, Map.of("A", "2"), "./mvnw test");
        assertEquals(2, sessionsCreated.get());

        buildContainerService.stopActiveContainer();
        assertTrue(sessionClosed.get());
    }

    @Test
    void testExecuteCommandPropagatesTestcontainersFailureWithoutFallback() {
        buildContainerService.setContainerSessionFactory(key -> {
            throw new IllegalStateException("client version 1.32 is too old. Minimum supported API version is 1.44");
        });

        ContainerConfig config = new ContainerConfig(true, "eclipse-temurin:25-jdk", "/workspace");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> buildContainerService.executeCommand(tempDir, config, Map.of(), "./mvnw compile")).getMessage()
                .contains("client version 1.32 is too old"));
    }

    @Test
    void testExecuteCommandStopsActiveContainerOnInterruptionOrCancellation() {
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        AtomicBoolean throwInterrupted = new AtomicBoolean(true);

        buildContainerService.setContainerSessionFactory(key -> {
            sessionClosed.set(false);
            return new ContainerSession() {
                @Override
                public ContainerExecResult execInContainer(String... command) throws Exception {
                    if (throwInterrupted.get()) {
                        throw new InterruptedException("exec interrupted");
                    }
                    throw new java.util.concurrent.CancellationException("exec cancelled");
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
        assertThrows(InterruptedException.class,
                () -> buildContainerService.executeCommand(tempDir, config, Map.of(), "./mvnw test"));
        assertTrue(sessionClosed.get(), "Active container session must be stopped when interrupted");

        throwInterrupted.set(false);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> buildContainerService.executeCommand(tempDir, config, Map.of(), "./mvnw test"));
        assertTrue(sessionClosed.get(), "Active container session must be stopped when cancelled");
    }

    @Test
    void testIsAndUpdateContainerEnabledInToml() {
        for (String invalid : new String[]{null, "", "   ", "[container\nenabled = true\n", "invalid = = toml"}) {
            assertFalse(BuildContainerService.isContainerEnabledInToml(invalid));
        }
        String tomlWithDisabled = "[build]\ncompile = './mvnw compile'\n\n[container]\nenabled = false # inline comment\nimage = 'eclipse-temurin:25-jdk'\n";

        String enabledToml = BuildContainerService.updateContainerEnabledInToml(tomlWithDisabled, true);
        assertTrue(BuildContainerService.isContainerEnabledInToml(enabledToml));
        assertTrue(enabledToml.contains("enabled = true # inline comment"));
        assertFalse(BuildContainerService
                .isContainerEnabledInToml(BuildContainerService.updateContainerEnabledInToml(enabledToml, false)));

        String appended = BuildContainerService.updateContainerEnabledInToml("""
                [build]
                compile = './mvnw compile'
                """, true);
        SwingTestUtils.assertContainsAll(appended, "[container]",
                "image = '" + BuildContainerService.DEFAULT_CONTAINER_IMAGE + "'",
                "workdir = '" + BuildContainerService.DEFAULT_CONTAINER_WORKDIR + "'");
        assertTrue(BuildContainerService.isContainerEnabledInToml(appended));

        String commentedSectionToml = """
                [build]
                compile = './mvnw'

                [container] # container settings
                enabled = false # comment
                """;
        String updatedCommented = BuildContainerService.updateContainerEnabledInToml(commentedSectionToml, true);
        assertEquals(1, updatedCommented.lines().filter(l -> l.contains("[container]")).count());
        assertTrue(updatedCommented.contains("[container] # container settings"));
        assertTrue(updatedCommented.contains("enabled = true # comment"));
        assertTrue(BuildContainerService.isContainerEnabledInToml(updatedCommented));

        String spacedSectionToml = """
                [ container ]
                enabled = false
                """;
        String updatedSpaced = BuildContainerService.updateContainerEnabledInToml(spacedSectionToml, true);
        assertEquals(1, updatedSpaced.lines().filter(l -> l.contains("container")).count());
        assertTrue(updatedSpaced.contains("enabled = true"));
        assertTrue(BuildContainerService.isContainerEnabledInToml(updatedSpaced));

        String transientSyntaxErrorInBuild = """
                [build]
                compile =

                [ container ] # comment
                enabled = true # inline
                """;
        assertTrue(BuildContainerService.isContainerEnabledInToml(transientSyntaxErrorInBuild));
        String transientDisabled = """
                [build]
                compile =

                [container]
                enabled = false
                """;
        assertFalse(BuildContainerService.isContainerEnabledInToml(transientDisabled));
    }
}
