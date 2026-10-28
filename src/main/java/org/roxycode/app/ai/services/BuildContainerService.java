package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.dockerjava.api.command.CreateContainerCmd;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
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
    static final String CONTAINER_NAME_PREFIX = "roxycode-build-";
    static final String LABEL_APP = "app";
    static final String LABEL_APP_VALUE = "roxycode";
    static final String LABEL_PROJECT = "roxycode.project";
    private static final String NL = "\n";
    private static final Pattern CONTAINER_SECTION_PATTERN = Pattern
            .compile("(?m)^[ \t]*\\[[ \t]*container[ \t]*\\][ \t]*(?:#.*)?$");
    private static final Pattern ANY_SECTION_PATTERN = Pattern.compile("(?m)^[ \t]*\\[[^\\]\r\n]+\\][ \t]*(?:#.*)?$");
    private static final Pattern ENABLED_LINE_PATTERN = Pattern
            .compile("(?m)^([ \t]*enabled[ \t]*=[ \t]*)(true|false)([ \t]*(?:#.*)?)$");
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContainerConfig(boolean enabled, String image, String workdir) {
        public static final ContainerConfig DEFAULT = new ContainerConfig(false, DEFAULT_CONTAINER_IMAGE,
                DEFAULT_CONTAINER_WORKDIR);

        public ContainerConfig {
            image = StringUtils.defaultIfBlank(StringUtils.trim(image), DEFAULT_CONTAINER_IMAGE);
            workdir = StringUtils.defaultIfBlank(StringUtils.trim(workdir), DEFAULT_CONTAINER_WORKDIR);
        }
    }

    public record ContainerSessionKey(Path projectRoot, String image, String workdir, Map<String, String> env) {
    }

    public record ContainerExecResult(int exitCode, String stdout, String stderr) {
        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.isNotEmpty(stdout))
                sb.append(stdout);
            if (StringUtils.isNotEmpty(stderr)) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n')
                    sb.append('\n');
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
        if (systemToolService == null)
            return false;
        CompletableFuture<Boolean> future = systemToolService.isDockerAvailable();
        return future != null ? Boolean.TRUE.equals(future.join()) : systemToolService.checkDockerAvailable();
    }

    public ContainerConfig parseContainerConfig(ContainerConfig config) {
        return config != null ? config : ContainerConfig.DEFAULT;
    }

    public ContainerExecResult executeCommand(Path projectRoot, ContainerConfig containerConfig,
            Map<String, String> env, String command) throws Exception {
        ContainerSessionKey sessionKey = new ContainerSessionKey(projectRoot.toAbsolutePath().normalize(),
                containerConfig.image(), containerConfig.workdir(), Map.copyOf(env));
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

    public static boolean isContainerEnabledInToml(String toml) {
        if (StringUtils.isBlank(toml))
            return false;
        try {
            var parsed = AiConfig.SHARED_TOML_MAPPER.readValue(toml, GenericBuildToolService.BuildConfig.class);
            return parsed != null && parsed.container().enabled();
        } catch (Exception e) {
            log.debug("Failed to parse full TOML when checking container.enabled flag; falling back to section scan",
                    e);
            Matcher sectionMatcher = CONTAINER_SECTION_PATTERN.matcher(toml);
            if (!sectionMatcher.find()) {
                return false;
            }
            int bodyStart = sectionMatcher.end();
            Matcher nextSection = ANY_SECTION_PATTERN.matcher(toml);
            int bodyEnd = nextSection.find(bodyStart) ? nextSection.start() : toml.length();
            Matcher enabledMatcher = ENABLED_LINE_PATTERN.matcher(toml.substring(bodyStart, bodyEnd));
            return enabledMatcher.find() && Boolean.parseBoolean(enabledMatcher.group(2));
        }
    }

    public static String updateContainerEnabledInToml(String toml, boolean enabled) {
        String safeToml = toml != null ? toml : "";
        Matcher sectionMatcher = CONTAINER_SECTION_PATTERN.matcher(safeToml);
        if (!sectionMatcher.find()) {
            String section = ("""
                    [container]
                    enabled = %s
                    image = '%s'
                    workdir = '%s'
                    """).formatted(enabled, DEFAULT_CONTAINER_IMAGE, DEFAULT_CONTAINER_WORKDIR);
            return safeToml.isEmpty() ? section : safeToml.stripTrailing() + NL + NL + section;
        }
        int bodyStart = sectionMatcher.end();
        Matcher nextSection = ANY_SECTION_PATTERN.matcher(safeToml);
        int bodyEnd = nextSection.find(bodyStart) ? nextSection.start() : safeToml.length();
        String body = safeToml.substring(bodyStart, bodyEnd);
        Matcher enabledMatcher = ENABLED_LINE_PATTERN.matcher(body);
        String updatedBody = enabledMatcher.find()
                ? enabledMatcher.replaceFirst("$1" + enabled + "$3")
                : NL + "enabled = " + enabled + body;
        return safeToml.substring(0, bodyStart) + updatedBody + safeToml.substring(bodyEnd);
    }

    static String buildContainerName(Path projectRoot) {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return CONTAINER_NAME_PREFIX + ProjectService.sanitizeProjectName(projectRoot) + "-" + uniqueSuffix;
    }

    static GenericContainer<?> createConfiguredContainer(ContainerSessionKey key) {
        String containerName = buildContainerName(key.projectRoot());
        Consumer<CreateContainerCmd> nameModifier = cmd -> cmd.withName(containerName);
        return new GenericContainer<>(DockerImageName.parse(key.image()))
                .withFileSystemBind(key.projectRoot().toAbsolutePath().toString(), key.workdir(), BindMode.READ_WRITE)
                .withWorkingDirectory(key.workdir()).withEnv(key.env()).withLabel(LABEL_APP, LABEL_APP_VALUE)
                .withLabel(LABEL_PROJECT, ProjectService.sanitizeProjectName(key.projectRoot()))
                .withCreateContainerCmdModifier(nameModifier)
                .withCommand("sh", "-c", "while true; do sleep 3600; done");
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
