package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.command.CreateContainerCmd;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.roxycode.app.ai.services.BuildContainerService.ContainerExecResult;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSession;
import org.roxycode.app.ai.services.BuildContainerService.ContainerSessionKey;
import org.roxycode.app.ai.services.BuildContainerService.SandboxConfig;
import org.roxycode.app.ai.services.BuildContainerService.SandboxMode;
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
    void testResolveHostUserSpec() {
        assertTrue(BuildContainerService.resolveHostUserSpec(null).isEmpty());
        Optional<String> existingSpec = BuildContainerService.resolveHostUserSpec(tempDir);
        Optional<String> nonExistentChildSpec = BuildContainerService
                .resolveHostUserSpec(tempDir.resolve("nonexistent/child"));
        assertEquals(existingSpec, nonExistentChildSpec);
        existingSpec.ifPresent(spec -> assertTrue(spec.matches("^[0-9]+:[0-9]+$")));
    }

    @Test
    void testCreateConfiguredContainerSetsIdentifiableNameLabelsUserAndHomeEnv() {
        ContainerSessionKey key = new ContainerSessionKey(tempDir.resolve("Roxy_Test_App"), "eclipse-temurin:25-jdk",
                "/workspace", Map.of("ROXY_CODE", "true"));

        GenericContainer<?> container = BuildContainerService.createConfiguredContainer(key);
        assertEquals("/workspace", container.getWorkingDirectory());
        assertEquals("true", container.getEnvMap().get("ROXY_CODE"));
        assertEquals("/tmp", container.getEnvMap().get("HOME"));
        assertEquals("roxycode", container.getLabels().get("app"));
        assertEquals("roxy-test-app", container.getLabels().get("roxycode.project"));
        assertFalse(container.getCreateContainerCmdModifiers().isEmpty());

        CreateContainerCmd mockCmd = mock(CreateContainerCmd.class);
        container.getCreateContainerCmdModifiers().forEach(m -> m.modify(mockCmd));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockCmd).withName(nameCaptor.capture());
        assertTrue(nameCaptor.getValue().matches("^roxycode-build-roxy-test-app-[a-f0-9]{8}$"));

        Optional<String> expectedUserSpec = BuildContainerService.resolveHostUserSpec(tempDir);
        if (expectedUserSpec.isPresent()) {
            verify(mockCmd).withUser(expectedUserSpec.get());
        } else {
            verify(mockCmd, never()).withUser(anyString());
        }

        ContainerSessionKey customHomeKey = new ContainerSessionKey(tempDir, "eclipse-temurin:25-jdk", "/workspace",
                Map.of("HOME", "/custom/home"));
        GenericContainer<?> customHomeContainer = BuildContainerService.createConfiguredContainer(customHomeKey);
        assertEquals("/custom/home", customHomeContainer.getEnvMap().get("HOME"));
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
                public ContainerExecResult execInContainer(java.time.Duration timeout, String... command) {
                    commands.add(command);
                    return new ContainerExecResult(0, "out", "err", false);
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

        SandboxConfig config = new SandboxConfig("docker", List.of(), "eclipse-temurin:25-jdk", "/workspace");
        ContainerExecResult res1 = buildContainerService.executeCommand(tempDir, config, Map.of("A", "1"),
                "./mvnw compile", java.time.Duration.ofMinutes(1));
        assertEquals(0, res1.exitCode());
        assertEquals("out\nerr", res1.combinedOutput());

        buildContainerService.executeCommand(tempDir, config, Map.of("A", "1"), "./mvnw test",
                java.time.Duration.ofMinutes(1));
        assertEquals(1, sessionsCreated.get());
        assertEquals(2, commands.size());

        buildContainerService.executeCommand(tempDir, config, Map.of("A", "2"), "./mvnw test",
                java.time.Duration.ofMinutes(1));
        assertEquals(2, sessionsCreated.get());

        buildContainerService.stopActiveContainer();
        assertTrue(sessionClosed.get());
    }

    @Test
    void testExecuteCommandPropagatesTestcontainersFailureWithoutFallback() {
        buildContainerService.setContainerSessionFactory(key -> {
            throw new IllegalStateException("client version 1.32 is too old. Minimum supported API version is 1.44");
        });

        SandboxConfig config = new SandboxConfig("docker", List.of(), "eclipse-temurin:25-jdk", "/workspace");
        assertTrue(
                assertThrows(IllegalStateException.class,
                        () -> buildContainerService.executeCommand(tempDir, config, Map.of(), "./mvnw compile",
                                java.time.Duration.ofMinutes(1)))
                        .getMessage().contains("client version 1.32 is too old"));
    }

    @Test
    void testExecuteCommandStopsActiveContainerOnInterruptionOrCancellation() {
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        AtomicBoolean throwInterrupted = new AtomicBoolean(true);

        buildContainerService.setContainerSessionFactory(key -> {
            sessionClosed.set(false);
            return new ContainerSession() {
                @Override
                public ContainerExecResult execInContainer(java.time.Duration timeout, String... command)
                        throws Exception {
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

        SandboxConfig config = new SandboxConfig("docker", List.of(), "eclipse-temurin:25-jdk", "/workspace");
        assertThrows(InterruptedException.class, () -> buildContainerService.executeCommand(tempDir, config, Map.of(),
                "./mvnw test", java.time.Duration.ofMinutes(1)));
        assertTrue(sessionClosed.get(), "Active container session must be stopped when interrupted");

        throwInterrupted.set(false);
        assertThrows(java.util.concurrent.CancellationException.class, () -> buildContainerService
                .executeCommand(tempDir, config, Map.of(), "./mvnw test", java.time.Duration.ofMinutes(1)));
        assertTrue(sessionClosed.get(), "Active container session must be stopped when cancelled");
    }

    @Test
    void testExecuteCommandStopsActiveContainerOnTimeout() throws Exception {
        AtomicBoolean sessionClosed = new AtomicBoolean(false);

        buildContainerService.setContainerSessionFactory(key -> {
            sessionClosed.set(false);
            return new ContainerSession() {
                @Override
                public ContainerExecResult execInContainer(java.time.Duration timeout, String... command) {
                    return new ContainerExecResult(-1, "", "timed out", true);
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

        SandboxConfig config = new SandboxConfig("docker", List.of(), "eclipse-temurin:25-jdk", "/workspace");
        ContainerExecResult res = buildContainerService.executeCommand(tempDir, config, Map.of(), "./mvnw test",
                java.time.Duration.ofMinutes(1));
        assertTrue(res.timedOut());
        assertTrue(sessionClosed.get(), "Active container session must be stopped when command times out");
    }

    @Test
    void testSandboxModeAndSandboxConfigNormalization() {
        assertEquals(SandboxMode.NONE, SandboxMode.fromString(null));
        assertEquals(SandboxMode.NONE, SandboxMode.fromString(""));
        assertEquals(SandboxMode.NONE, SandboxMode.fromString("  none  "));
        assertEquals(SandboxMode.NONE, SandboxMode.fromString("unknown"));
        assertEquals(SandboxMode.NATIVE, SandboxMode.fromString("native"));
        assertEquals(SandboxMode.NATIVE, SandboxMode.fromString("  NATIVE "));
        assertEquals(SandboxMode.DOCKER, SandboxMode.fromString("docker"));
        assertEquals(SandboxMode.DOCKER, SandboxMode.fromString(" DOCKER "));

        SandboxConfig defaultCfg = new SandboxConfig(null, null, "  ", null);
        assertEquals("none", defaultCfg.mode());
        assertEquals(SandboxMode.NONE, defaultCfg.resolvedMode());
        assertTrue(defaultCfg.writablePaths().isEmpty());
        assertEquals(BuildContainerService.DEFAULT_CONTAINER_IMAGE, defaultCfg.image());
        assertEquals(BuildContainerService.DEFAULT_CONTAINER_WORKDIR, defaultCfg.workdir());

        SandboxConfig customCfg = new SandboxConfig(" native ", List.of("~/.sdkman"), List.of("~/.m2"),
                " eclipse-temurin:25-jdk ", " /app ");
        assertEquals("native", customCfg.mode());
        assertEquals(SandboxMode.NATIVE, customCfg.resolvedMode());
        assertEquals(List.of("~/.sdkman"), customCfg.readablePaths());
        assertEquals(List.of("~/.m2"), customCfg.writablePaths());
        assertEquals("eclipse-temurin:25-jdk", customCfg.image());
        assertEquals("/app", customCfg.workdir());
    }

    @Test
    void testResolveWritablePathsAndNativeSandboxCommandBuilders() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("my-project"));
        Path existingSubdir = Files.createDirectories(projectRoot.resolve("target-cache"));
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path existingSdkman = Files.createDirectories(fakeHome.resolve(".sdkman"));
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", fakeHome.toString());
            List<Path> resolvedReadable = BuildContainerService
                    .resolveReadablePaths(List.of("~/.sdkman", "~/.missing-jdk", "  "), projectRoot);
            assertEquals(List.of(existingSdkman.toAbsolutePath().normalize()), resolvedReadable);
            assertFalse(Files.exists(fakeHome.resolve(".missing-jdk")),
                    "resolveReadablePaths must not create missing directories");

            List<Path> resolved = BuildContainerService.resolveWritablePaths(
                    List.of("~/.m2/repo", "target-cache", "non-existent-relative", "~/.m2/repo", "  "), projectRoot);
            Path expectedHomeCache = fakeHome.resolve(".m2/repo").toAbsolutePath().normalize();
            assertTrue(Files.isDirectory(expectedHomeCache), "Home cache directory should be created automatically");
            assertEquals(List.of(expectedHomeCache, existingSubdir.toAbsolutePath().normalize()), resolved);

            String rootStr = projectRoot.toAbsolutePath().normalize().toString();
            List<String> bwrapCmd = BuildContainerService.buildBubblewrapCommand(projectRoot, resolvedReadable,
                    resolved, "./mvnw compile");
            assertTrue(bwrapCmd.containsAll(List.of("bwrap", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp")));
            // System binds (at least /usr and /bin should exist on most systems)
            if (Files.exists(Path.of("/usr"))) {
                assertTrue(bwrapCmd.contains("/usr"));
            }
            if (Files.exists(Path.of("/bin"))) {
                assertTrue(bwrapCmd.contains("/bin"));
            }
            if (Files.exists(Path.of("/opt"))) {
                assertTrue(bwrapCmd.contains("/opt"));
            }
            // Readable binds mounted via --ro-bind before writable binds
            int sdkmanIndex = bwrapCmd.indexOf(existingSdkman.toAbsolutePath().normalize().toString());
            int m2Index = bwrapCmd.indexOf(expectedHomeCache.toString());
            assertTrue(sdkmanIndex > 0);
            assertEquals("--ro-bind", bwrapCmd.get(sdkmanIndex - 1));
            assertTrue(sdkmanIndex < m2Index, "Readable paths must be bound before writable paths");
            assertEquals("--bind", bwrapCmd.get(m2Index - 1));
            assertTrue(bwrapCmd.contains(existingSubdir.toAbsolutePath().normalize().toString()));
            // Project root bind
            assertTrue(bwrapCmd.contains(rootStr));
            // Final sh call
            assertEquals("sh", bwrapCmd.get(bwrapCmd.size() - 3));
            assertEquals("-c", bwrapCmd.get(bwrapCmd.size() - 2));
            assertEquals("./mvnw compile", bwrapCmd.get(bwrapCmd.size() - 1));

            String dq = String.valueOf((char) 34);
            String bs = String.valueOf((char) 92);
            Path pathWithQuote = fakeHome.resolve("dir" + dq + "quote" + bs + "slash");
            String seatbeltProfile = BuildContainerService.buildSeatbeltProfile(projectRoot, resolvedReadable,
                    List.of(pathWithQuote));
            SwingTestUtils
                    .assertContainsAll(seatbeltProfile, "(version 1)", "(deny default)", "(allow file-read-metadata)",
                            "(subpath " + dq + "/usr" + dq + ")", "(subpath " + dq + "/opt" + dq + ")",
                            "(allow file-read* (subpath " + dq + existingSdkman.toAbsolutePath().normalize() + dq
                                    + "))",
                            "(allow file-read* file-write* (subpath " + dq + rootStr + dq + "))",
                            "dir" + bs + dq + "quote" + bs + bs + "slash");

            List<String> seatbeltCmd = BuildContainerService.buildSeatbeltCommand(projectRoot, resolved, "./mvnw test");
            assertEquals("/usr/bin/sandbox-exec", seatbeltCmd.get(0));
            assertEquals("-p", seatbeltCmd.get(1));
            assertEquals(BuildContainerService.buildSeatbeltProfile(projectRoot, resolved), seatbeltCmd.get(2));
            assertEquals("sh", seatbeltCmd.get(3));
            assertEquals("-c", seatbeltCmd.get(4));
            assertEquals("./mvnw test", seatbeltCmd.get(5));

            SandboxConfig nativeCfg = new SandboxConfig("native", List.of("~/.sdkman"), List.of("~/.m2/repo"),
                    "ubuntu:24.04", "/workspace");
            if (SystemUtils.IS_OS_LINUX) {
                List<String> dispatched = buildContainerService.buildNativeSandboxCommand(projectRoot, nativeCfg,
                        "echo ok");
                assertEquals("bwrap", dispatched.get(0));
            } else if (SystemUtils.IS_OS_MAC) {
                List<String> dispatched = buildContainerService.buildNativeSandboxCommand(projectRoot, nativeCfg,
                        "echo ok");
                assertEquals("/usr/bin/sandbox-exec", dispatched.get(0));
            }
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }
}
