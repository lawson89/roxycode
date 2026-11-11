package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.command.CreateContainerCmd;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jspecify.annotations.Nullable;
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
class BuildContainerService {

    private static final Logger log = LoggerFactory.getLogger(BuildContainerService.class);
    private static final ExecutorService DOCKER_EXEC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    static final String DEFAULT_CONTAINER_IMAGE = "ubuntu:24.04";
    static final String DEFAULT_CONTAINER_WORKDIR = "/workspace";
    static final String DEFAULT_CONTAINER_HOME = "/tmp";
    static final String CONTAINER_NAME_PREFIX = "roxycode-build-";
    private static final String ENV_HOME = "HOME";
    static final String LABEL_APP = "app";
    static final String LABEL_APP_VALUE = "roxycode";
    static final String LABEL_PROJECT = "roxycode.project";
    private static final String NL = "\n";
    private static final String BS = "\\";
    private static final String DQ = "\"";
    private static final String TILDE = "~";
    private static final String TILDE_SLASH = "~/";
    private static final String TILDE_BACKSLASH = "~" + BS;

    public enum SandboxMode {
        NONE, NATIVE, DOCKER;

        private static final String MODE_NONE = "none";
        private static final String MODE_NATIVE = "native";
        private static final String MODE_DOCKER = "docker";

        public static boolean isValidMode(@Nullable String value) {
            String trimmed = StringUtils.trimToEmpty(value);
            if (trimmed.isEmpty() || MODE_NONE.equalsIgnoreCase(trimmed)) {
                return true;
            }
            return MODE_NATIVE.equalsIgnoreCase(trimmed) || MODE_DOCKER.equalsIgnoreCase(trimmed);
        }

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

    public record SandboxConfig(String mode, @JsonProperty("readable_paths") List<String> readablePaths,
            @JsonProperty("writable_paths") List<String> writablePaths, String image, String workdir) {
        public static final SandboxConfig DEFAULT = new SandboxConfig("none", List.of(), List.of(),
                DEFAULT_CONTAINER_IMAGE, DEFAULT_CONTAINER_WORKDIR);

        public SandboxConfig {
            mode = StringUtils.defaultIfBlank(mode, "none").trim();
            readablePaths = List.copyOf(Objects.requireNonNullElse(readablePaths, List.of()));
            writablePaths = List.copyOf(Objects.requireNonNullElse(writablePaths, List.of()));
            image = StringUtils.defaultIfBlank(image, DEFAULT_CONTAINER_IMAGE).trim();
            workdir = StringUtils.defaultIfBlank(workdir, DEFAULT_CONTAINER_WORKDIR).trim();
        }

        public SandboxConfig(String mode, List<String> writablePaths, String image, String workdir) {
            this(mode, List.of(), writablePaths, image, workdir);
        }

        public SandboxMode resolvedMode() {
            return SandboxMode.fromString(mode);
        }
    }

    public record ContainerSessionKey(Path projectRoot, String image, String workdir, Map<String, String> env) {
    }

    public record ContainerExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.isNotEmpty(stdout)) {
                sb.append(stdout);
            }
            if (StringUtils.isNotEmpty(stderr)) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                sb.append(stderr);
            }
            return sb.toString();
        }
    }

    interface ContainerSession extends AutoCloseable {
        ContainerExecResult execInContainer(java.time.Duration timeout, String... command) throws Exception;

        boolean isRunning();

        @Override
        void close();
    }

    @FunctionalInterface
    interface ContainerSessionFactory {
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
        List<String> rawReadable = new ArrayList<>();
        List<String> rawWritable = List.of();
        if (sandboxConfig != null) {
            rawReadable.addAll(sandboxConfig.readablePaths());
            rawWritable = sandboxConfig.writablePaths();
        }
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && !javaHomeEnv.isBlank()) {
            rawReadable.add(javaHomeEnv.trim());
        }
        String javaHomeProp = System.getProperty("java.home");
        if (javaHomeProp != null && !javaHomeProp.isBlank()) {
            rawReadable.add(javaHomeProp.trim());
        }
        List<Path> readablePaths = resolveReadablePaths(rawReadable, projectRoot);
        List<Path> writablePaths = resolveWritablePaths(rawWritable, projectRoot);
        if (SystemUtils.IS_OS_MAC) {
            return buildSeatbeltCommand(projectRoot, readablePaths, writablePaths, command);
        }
        if (SystemUtils.IS_OS_LINUX) {
            return buildBubblewrapCommand(projectRoot, readablePaths, writablePaths, command);
        }
        throw new IllegalStateException("Native sandbox is not supported on OS: " + SystemUtils.OS_NAME);
    }

    static List<Path> resolveReadablePaths(List<String> rawPaths, Path projectRoot) {
        return resolveSandboxPaths(rawPaths, projectRoot, false);
    }

    static List<Path> resolveWritablePaths(List<String> rawPaths, Path projectRoot) {
        return resolveSandboxPaths(rawPaths, projectRoot, true);
    }

    private static List<Path> resolveSandboxPaths(List<String> rawPaths, Path projectRoot, boolean createIfMissing) {
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
            if (createIfMissing && homeDir != null && resolved.startsWith(homeDir) && !Files.exists(resolved)) {
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
        return buildBubblewrapCommand(projectRoot, List.of(), writablePaths, command);
    }

    static List<String> buildBubblewrapCommand(Path projectRoot, List<Path> readablePaths, List<Path> writablePaths,
            String command) {
        String rootStr = projectRoot.toAbsolutePath().normalize().toString();
        List<String> args = new ArrayList<>();
        Collections.addAll(args, "bwrap", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp");

        // Essential system directories for binaries, toolchains, and libraries
        List<String> systemPaths = List.of("/usr", "/bin", "/lib", "/lib64", "/opt", "/etc/alternatives", "/etc/ssl",
                "/etc/pki", "/etc/resolv.conf", "/etc/hosts");
        for (String path : systemPaths) {
            if (Files.exists(Path.of(path))) {
                Collections.addAll(args, "--ro-bind", path, path);
            }
        }

        if (readablePaths != null) {
            for (Path p : readablePaths) {
                if (p != null) {
                    String pathStr = p.toAbsolutePath().normalize().toString();
                    Collections.addAll(args, "--ro-bind", pathStr, pathStr);
                }
            }
        }

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
        return buildSeatbeltProfile(projectRoot, List.of(), writablePaths);
    }

    static String buildSeatbeltProfile(Path projectRoot, List<Path> readablePaths, List<Path> writablePaths) {
        String rootStr = escapeSeatbeltPath(projectRoot.toAbsolutePath().normalize().toString());
        StringBuilder sb = new StringBuilder();
        sb.append("""
                (version 1)
                (deny default)
                (allow file-read-metadata)
                (allow process*)
                (allow signal*)
                (allow sysctl-read)
                (allow network-outbound)
                (allow mach-lookup)
                (allow file-read*
                  (subpath "/usr")
                  (subpath "/bin")
                  (subpath "/lib")
                  (subpath "/opt")
                  (subpath "/System")
                  (subpath "/Library")
                  (subpath "/etc")
                  (subpath "/private/etc")
                  (subpath "/dev")
                  (subpath "/private/var/folders")
                  (subpath "/var/folders")
                )
                (allow file-write*
                  (subpath "/private/tmp")
                  (subpath "/tmp")
                  (subpath "/private/var/folders")
                  (subpath "/var/folders")
                  (literal "/dev/null")
                  (literal "/dev/zero")
                  (literal "/dev/random")
                  (literal "/dev/urandom")
                )
                """);

        if (readablePaths != null) {
            for (Path p : readablePaths) {
                if (p != null) {
                    String escaped = escapeSeatbeltPath(p.toAbsolutePath().normalize().toString());
                    sb.append("  (allow file-read* (subpath ").append(DQ).append(escaped).append(DQ).append("))")
                            .append(NL);
                }
            }
        }

        // Project root access
        sb.append("  (allow file-read* file-write* (subpath ").append(DQ).append(rootStr).append(DQ).append("))")
                .append(NL);

        if (writablePaths != null) {
            for (Path p : writablePaths) {
                if (p != null) {
                    String escaped = escapeSeatbeltPath(p.toAbsolutePath().normalize().toString());
                    sb.append("  (allow file-read* file-write* (subpath ").append(DQ).append(escaped).append(DQ)
                            .append("))").append(NL);
                }
            }
        }
        return sb.toString();
    }

    private static String escapeSeatbeltPath(String path) {
        return path.replace(BS, BS + BS).replace(DQ, BS + DQ);
    }

    static List<String> buildSeatbeltCommand(Path projectRoot, List<Path> writablePaths, String command) {
        return buildSeatbeltCommand(projectRoot, List.of(), writablePaths, command);
    }

    static List<String> buildSeatbeltCommand(Path projectRoot, List<Path> readablePaths, List<Path> writablePaths,
            String command) {
        String profile = buildSeatbeltProfile(projectRoot, readablePaths, writablePaths);
        return List.of("/usr/bin/sandbox-exec", "-p", profile, "sh", "-c", command);
    }

    public ContainerExecResult executeCommand(Path projectRoot, SandboxConfig sandboxConfig, Map<String, String> env,
            String command, java.time.Duration timeout) throws Exception {
        ContainerSessionKey sessionKey = new ContainerSessionKey(projectRoot.toAbsolutePath().normalize(),
                sandboxConfig.image(), sandboxConfig.workdir(), Map.copyOf(env));
        try {
            ContainerExecResult result = getOrCreateContainerSession(sessionKey).execInContainer(timeout, "sh", "-c",
                    command);
            if (result.timedOut()) {
                stopActiveContainer();
            }
            return result;
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
            @SuppressWarnings("PMD.PreserveStackTrace")
            public ContainerExecResult execInContainer(java.time.Duration timeout, String... command) throws Exception {
                CompletableFuture<Container.ExecResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return container.execInContainer(StandardCharsets.UTF_8, command);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, DOCKER_EXEC_EXECUTOR);

                try {
                    Container.ExecResult r = future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    return new ContainerExecResult(r.getExitCode(), r.getStdout(), r.getStderr(), false);
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    try {
                        container.stop();
                    } catch (Exception ex) {
                        log.debug("Failed to stop timed-out container: {}", ex.getMessage());
                    }
                    return new ContainerExecResult(-1, "",
                            "Command timed out after " + timeout.toSeconds() + " seconds", true);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof Exception ex) {
                        throw ex;
                    }
                    throw e;
                }
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
