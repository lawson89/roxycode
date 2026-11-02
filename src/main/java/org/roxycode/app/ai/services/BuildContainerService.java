package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.command.CreateContainerCmd;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SystemToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Service
@SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
public class BuildContainerService {

    private static final Logger log = LoggerFactory.getLogger(BuildContainerService.class);

    static final String DEFAULT_CONTAINER_IMAGE = "ubuntu:24.04";
    static final String DEFAULT_CONTAINER_WORKDIR = "/workspace";
    static final String DEFAULT_CONTAINER_HOME = "/tmp";
    static final String CONTAINER_NAME_PREFIX = "roxycode-build-";
    private static final String ENV_HOME = "HOME";
    static final String LABEL_APP = "app";
    static final String LABEL_APP_VALUE = "roxycode";
    static final String LABEL_PROJECT = "roxycode.project";
    private static final String NL = String.valueOf((char) 10);
    private static final String BS = String.valueOf((char) 92);
    private static final String DQ = String.valueOf((char) 34);
    private static final String TILDE = "~";
    private static final String TILDE_SLASH = "~/";
    private static final String TILDE_BACKSLASH = "~" + BS;

    public enum SandboxMode {
        NONE, NATIVE, DOCKER;

        private static final String MODE_NATIVE = "native";
        private static final String MODE_DOCKER = "docker";

        public static SandboxMode fromString(String value) {
            String trimmed = StringUtils.trimToEmpty(value);
            if (MODE_NATIVE.equalsIgnoreCase(trimmed)) {
                return NATIVE;
            }
            if (MODE_DOCKER.equalsIgnoreCase(trimmed)) {
                return DOCKER;
            }
            return NONE;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SandboxConfig(String mode, @JsonProperty("writable_paths") List<String> writablePaths, String image,
            String workdir) {
        public static final SandboxConfig DEFAULT = new SandboxConfig("none", List.of(), DEFAULT_CONTAINER_IMAGE,
                DEFAULT_CONTAINER_WORKDIR);

        public SandboxConfig {
            mode = StringUtils.defaultIfBlank(mode, "none").trim();
            writablePaths = List.copyOf(Objects.requireNonNullElse(writablePaths, List.of()));
            image = StringUtils.defaultIfBlank(image, DEFAULT_CONTAINER_IMAGE).trim();
            workdir = StringUtils.defaultIfBlank(workdir, DEFAULT_CONTAINER_WORKDIR).trim();
        }

        public SandboxMode resolvedMode() {
            return SandboxMode.fromString(mode);
        }
    }

    public record ContainerSessionKey(Path projectRoot, String image, String workdir, Map<String, String> env) {
    }

    public record ContainerExecResult(int exitCode, String stdout, String stderr) {
        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.isNotEmpty(stdout)) {
                sb.append(stdout);
            }
            if (StringUtils.isNotEmpty(stderr)) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != (char) 10) {
                    sb.append((char) 10);
                }
                sb.append(stderr);
            }
            return sb.toString();
        }
    }

    public interface ContainerSession extends AutoCloseable {
        ContainerExecResult execInContainer(String... command) throws Exception;

        boolean isRunning();

        @Override
        void close();
    }

    @FunctionalInterface
    public interface ContainerSessionFactory {
        ContainerSession create(ContainerSessionKey key) throws Exception;
    }

    private final SystemToolService systemToolService;
    private ContainerSessionFactory containerSessionFactory = BuildContainerService::createContainerSession;
    private ContainerSession activeSession;
    private ContainerSessionKey activeSessionKey;

    public BuildContainerService(SystemToolService systemToolService) {
        this.systemToolService = systemToolService;
    }

    void setContainerSessionFactory(ContainerSessionFactory containerSessionFactory) {
        this.containerSessionFactory = containerSessionFactory;
    }

    @PreDestroy
    public synchronized void stopActiveContainer() {
        if (activeSession != null) {
            try {
                activeSession.close();
            } catch (Exception e) {
                log.warn("Failed to stop active build container: {}", e.getMessage());
            } finally {
                activeSession = null;
                activeSessionKey = null;
            }
        }
    }

    public boolean isDockerAvailable() {
        if (systemToolService == null) {
            return false;
        }
        CompletableFuture<Boolean> future = systemToolService.isDockerAvailable();
        if (future != null) {
            return Boolean.TRUE.equals(future.join());
        }
        return systemToolService.checkDockerAvailable();
    }

    public boolean isNativeSandboxAvailable() {
        return systemToolService != null && systemToolService.checkNativeSandboxAvailable();
    }

    public List<String> buildNativeSandboxCommand(Path projectRoot, SandboxConfig sandboxConfig, String command) {
        List<String> rawWritable = List.of();
        if (sandboxConfig != null) {
            rawWritable = sandboxConfig.writablePaths();
        }
        List<Path> writablePaths = resolveWritablePaths(rawWritable, projectRoot);
        if (SystemUtils.IS_OS_MAC) {
            return buildSeatbeltCommand(projectRoot, writablePaths, command);
        }
        if (SystemUtils.IS_OS_LINUX) {
            return buildBubblewrapCommand(projectRoot, writablePaths, command);
        }
        throw new IllegalStateException("Native sandbox is not supported on OS: " + SystemUtils.OS_NAME);
    }

    static List<Path> resolveWritablePaths(List<String> rawPaths, Path projectRoot) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return List.of();
        }
        String userHomeProp = System.getProperty("user.home");
        Path homeDir = null;
        if (userHomeProp != null && !userHomeProp.isBlank()) {
            homeDir = Path.of(userHomeProp).toAbsolutePath().normalize();
        }
        Set<Path> resolvedSet = new LinkedHashSet<>();
        for (String raw : rawPaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim();
            Path resolved;
            if (TILDE.equals(trimmed)) {
                if (homeDir == null) {
                    continue;
                }
                resolved = homeDir;
            } else if (trimmed.startsWith(TILDE_SLASH) || trimmed.startsWith(TILDE_BACKSLASH)) {
                if (homeDir == null) {
                    continue;
                }
                resolved = homeDir.resolve(trimmed.substring(2)).toAbsolutePath().normalize();
            } else {
                Path candidate = Path.of(trimmed);
                if (candidate.isAbsolute()) {
                    resolved = candidate.toAbsolutePath().normalize();
                } else if (projectRoot != null) {
                    resolved = projectRoot.toAbsolutePath().normalize().resolve(candidate).normalize();
                } else {
                    resolved = candidate.toAbsolutePath().normalize();
                }
            }
            if (homeDir != null && resolved.startsWith(homeDir) && !Files.exists(resolved)) {
                try {
                    Files.createDirectories(resolved);
                } catch (IOException e) {
                    log.debug("Could not create writable cache directory {}: {}", resolved, e.getMessage());
                }
            }
            if (Files.exists(resolved)) {
                resolvedSet.add(resolved);
            }
        }
        return List.copyOf(resolvedSet);
    }

    static List<String> buildBubblewrapCommand(Path projectRoot, List<Path> writablePaths, String command) {
        String rootStr = projectRoot.toAbsolutePath().normalize().toString();
        List<String> args = new ArrayList<>();
        Collections.addAll(args, "bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp");
        if (writablePaths != null) {
            for (Path p : writablePaths) {
                if (p != null) {
                    String pathStr = p.toAbsolutePath().normalize().toString();
                    Collections.addAll(args, "--bind", pathStr, pathStr);
                }
            }
        }
        Collections.addAll(args, "--bind", rootStr, rootStr, "--chdir", rootStr, "--die-with-parent", "sh", "-c",
                command);
        return List.copyOf(args);
    }

    static String buildSeatbeltProfile(Path projectRoot, List<Path> writablePaths) {
        String rootStr = escapeSeatbeltPath(projectRoot.toAbsolutePath().normalize().toString());
        StringBuilder sb = new StringBuilder();
        sb.append("""
                (version 1)
                (allow default)
                (deny file-write*)
                (allow file-write*
                  (subpath "/private/tmp")
                  (subpath "/tmp")
                  (subpath "/private/var/folders")
                  (subpath "/var/folders")
                  (literal "/dev/null")
                  (literal "/dev/zero")
                  (literal "/dev/random")
                  (literal "/dev/urandom")
                """);
        sb.append("  (subpath ").append(DQ).append(rootStr).append(DQ).append(")").append(NL);
        if (writablePaths != null) {
            for (Path p : writablePaths) {
                if (p != null) {
                    String escaped = escapeSeatbeltPath(p.toAbsolutePath().normalize().toString());
                    sb.append("  (subpath ").append(DQ).append(escaped).append(DQ).append(")").append(NL);
                }
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private static String escapeSeatbeltPath(String path) {
        return path.replace(BS, BS + BS).replace(DQ, BS + DQ);
    }

    static List<String> buildSeatbeltCommand(Path projectRoot, List<Path> writablePaths, String command) {
        String profile = buildSeatbeltProfile(projectRoot, writablePaths);
        return List.of("/usr/bin/sandbox-exec", "-p", profile, "sh", "-c", command);
    }

    public ContainerExecResult executeCommand(Path projectRoot, SandboxConfig sandboxConfig, Map<String, String> env,
            String command) throws Exception {
        ContainerSessionKey sessionKey = new ContainerSessionKey(projectRoot.toAbsolutePath().normalize(),
                sandboxConfig.image(), sandboxConfig.workdir(), Map.copyOf(env));
        try {
            return getOrCreateContainerSession(sessionKey).execInContainer("sh", "-c", command);
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            stopActiveContainer();
            throw e;
        }
    }

    synchronized ContainerSession getOrCreateContainerSession(ContainerSessionKey key) throws Exception {
        if (activeSession != null && key.equals(activeSessionKey) && activeSession.isRunning()) {
            return activeSession;
        }
        stopActiveContainer();
        activeSession = containerSessionFactory.create(key);
        activeSessionKey = key;
        return activeSession;
    }

    static String buildContainerName(Path projectRoot) {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return CONTAINER_NAME_PREFIX + ProjectService.sanitizeProjectName(projectRoot) + "-" + uniqueSuffix;
    }

    static Optional<String> resolveHostUserSpec(Path projectRoot) {
        if (projectRoot == null) {
            return Optional.empty();
        }
        try {
            Path target = projectRoot.toAbsolutePath().normalize();
            while (target != null && !Files.exists(target)) {
                target = target.getParent();
            }
            if (target == null) {
                return Optional.empty();
            }
            Object uid = Files.getAttribute(target, "unix:uid");
            Object gid = Files.getAttribute(target, "unix:gid");
            if (uid instanceof Number && gid instanceof Number) {
                return Optional.of(uid + ":" + gid);
            }
        } catch (Exception e) {
            log.debug("Unix uid/gid attributes unavailable for {}: {}", projectRoot, e.getMessage());
        }
        return Optional.empty();
    }

    static GenericContainer<?> createConfiguredContainer(ContainerSessionKey key) {
        String containerName = buildContainerName(key.projectRoot());
        Optional<String> hostUserSpec = resolveHostUserSpec(key.projectRoot());
        Consumer<CreateContainerCmd> cmdModifier = cmd -> {
            cmd.withName(containerName);
            hostUserSpec.ifPresent(cmd::withUser);
        };
        Map<String, String> containerEnv = new LinkedHashMap<>();
        containerEnv.put(ENV_HOME, DEFAULT_CONTAINER_HOME);
        if (key.env() != null) {
            containerEnv.putAll(key.env());
        }
        return new GenericContainer<>(DockerImageName.parse(key.image()))
                .withFileSystemBind(key.projectRoot().toAbsolutePath().toString(), key.workdir(), BindMode.READ_WRITE)
                .withWorkingDirectory(key.workdir()).withEnv(containerEnv).withLabel(LABEL_APP, LABEL_APP_VALUE)
                .withLabel(LABEL_PROJECT, ProjectService.sanitizeProjectName(key.projectRoot()))
                .withCreateContainerCmdModifier(cmdModifier).withCommand("sh", "-c", "while true; do sleep 3600; done");
    }

    private static ContainerSession createContainerSession(ContainerSessionKey key) {
        GenericContainer<?> container = createConfiguredContainer(key);
        container.start();
        return new ContainerSession() {
            @Override
            public ContainerExecResult execInContainer(String... command) throws Exception {
                Container.ExecResult r = container.execInContainer(StandardCharsets.UTF_8, command);
                return new ContainerExecResult(r.getExitCode(), r.getStdout(), r.getStderr());
            }

            @Override
            public boolean isRunning() {
                return container.isRunning();
            }

            @Override
            public void close() {
                container.stop();
            }
        };
    }
}
