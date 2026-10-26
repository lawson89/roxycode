package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.dockerjava.api.command.CreateContainerCmd;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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

/**
 * Service responsible for managing warm reusable Docker build containers via Testcontainers,
 * including container configuration parsing, lifecycle management, and identifiable naming.
 */
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContainerConfig(boolean enabled, String image, String workdir) {
        public static final ContainerConfig DEFAULT =
                new ContainerConfig(false, DEFAULT_CONTAINER_IMAGE, DEFAULT_CONTAINER_WORKDIR);

        public ContainerConfig {
            image = StringUtils.defaultIfBlank(StringUtils.trim(image), DEFAULT_CONTAINER_IMAGE);
            workdir =
                    StringUtils.defaultIfBlank(
                            StringUtils.trim(workdir), DEFAULT_CONTAINER_WORKDIR);
        }
    }

    public record ContainerSessionKey(
            Path projectRoot, String image, String workdir, Map<String, String> env) {}

    public record ContainerExecResult(int exitCode, String stdout, String stderr) {
        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (stderr != null && !stderr.isEmpty()) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
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
    private ContainerSessionFactory containerSessionFactory;
    private ContainerSession activeSession;
    private ContainerSessionKey activeSessionKey;

    public BuildContainerService(SystemToolService systemToolService) {
        this.systemToolService = systemToolService;
        this.containerSessionFactory = BuildContainerService::createContainerSession;
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

    public ContainerConfig parseContainerConfig(ContainerConfig config) {
        return config != null ? config : ContainerConfig.DEFAULT;
    }

    public ContainerConfig parseContainerConfig(GenericBuildToolService.BuildConfig config) {
        return config != null && config.container() != null
                ? config.container()
                : ContainerConfig.DEFAULT;
    }

    public ContainerExecResult executeCommand(
            Path projectRoot,
            ContainerConfig containerConfig,
            Map<String, String> env,
            String command)
            throws Exception {
        ContainerSessionKey sessionKey =
                new ContainerSessionKey(
                        projectRoot.toAbsolutePath().normalize(),
                        containerConfig.image(),
                        containerConfig.workdir(),
                        Map.copyOf(env));
        ContainerSession session = getOrCreateContainerSession(sessionKey);
        return session.execInContainer("sh", "-c", command);
    }

    synchronized ContainerSession getOrCreateContainerSession(ContainerSessionKey key)
            throws Exception {
        if (activeSession != null && key.equals(activeSessionKey) && activeSession.isRunning()) {
            return activeSession;
        }
        stopActiveContainer();
        activeSession = containerSessionFactory.create(key);
        activeSessionKey = key;
        return activeSession;
    }

    public static boolean isContainerEnabledInToml(String toml) {
        if (StringUtils.isBlank(toml)) {
            return false;
        }
        try {
            GenericBuildToolService.BuildConfig parsed =
                    AiConfig.SHARED_TOML_MAPPER.readValue(
                            toml, GenericBuildToolService.BuildConfig.class);
            return parsed != null && parsed.container().enabled();
        } catch (Exception e) {
            log.debug("Failed to parse TOML when checking container.enabled flag", e);
            return false;
        }
    }

    public static String updateContainerEnabledInToml(String toml, boolean enabled) {
        String safeToml = toml != null ? toml : "";
        List<String> lines = safeToml.lines().toList();
        boolean foundContainerSection = false;
        boolean updatedEnabledLine = false;
        int containerSectionIndex = -1;
        List<String> outputLines = new ArrayList<>();

        boolean inContainerSection = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inContainerSection = "[container]".equals(trimmed);
                if (inContainerSection) {
                    foundContainerSection = true;
                    containerSectionIndex = i;
                }
                outputLines.add(line);
            } else if (inContainerSection && !updatedEnabledLine && isEnabledAssignment(trimmed)) {
                int eqIdx = line.indexOf('=');
                String prefix = line.substring(0, eqIdx + 1);
                String rest = line.substring(eqIdx + 1);
                int commentIdx = rest.indexOf('#');
                String commentSuffix = commentIdx >= 0 ? " " + rest.substring(commentIdx) : "";
                outputLines.add(prefix + " " + enabled + commentSuffix);
                updatedEnabledLine = true;
            } else {
                outputLines.add(line);
            }
        }

        if (!foundContainerSection) {
            String section =
                    """
                    [container]
                    enabled = %s
                    image = '%s'
                    workdir = '%s'
                    """
                            .formatted(enabled, DEFAULT_CONTAINER_IMAGE, DEFAULT_CONTAINER_WORKDIR);
            if (safeToml.isEmpty()) {
                return section;
            }
            return safeToml.stripTrailing() + "\n\n" + section;
        }

        if (!updatedEnabledLine && containerSectionIndex >= 0) {
            outputLines.add(containerSectionIndex + 1, "enabled = " + enabled);
        }

        return String.join("\n", outputLines);
    }

    private static boolean isEnabledAssignment(String trimmed) {
        if (!trimmed.startsWith("enabled")) {
            return false;
        }
        String afterKey = trimmed.substring("enabled".length()).trim();
        return afterKey.startsWith("=");
    }

    static String buildContainerName(Path projectRoot) {
        String projectSlug = ProjectService.sanitizeProjectName(projectRoot);
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return CONTAINER_NAME_PREFIX + projectSlug + "-" + uniqueSuffix;
    }

    static GenericContainer<?> createConfiguredContainer(ContainerSessionKey key) {
        String projectName = ProjectService.sanitizeProjectName(key.projectRoot());
        String containerName = buildContainerName(key.projectRoot());
        Consumer<CreateContainerCmd> nameModifier = cmd -> cmd.withName(containerName);
        return new GenericContainer<>(DockerImageName.parse(key.image()))
                .withFileSystemBind(
                        key.projectRoot().toAbsolutePath().toString(),
                        key.workdir(),
                        BindMode.READ_WRITE)
                .withWorkingDirectory(key.workdir())
                .withEnv(key.env())
                .withLabel(LABEL_APP, LABEL_APP_VALUE)
                .withLabel(LABEL_PROJECT, projectName)
                .withCreateContainerCmdModifier(nameModifier)
                .withCommand("sh", "-c", "while true; do sleep 3600; done");
    }

    private static ContainerSession createContainerSession(ContainerSessionKey key) {
        GenericContainer<?> container = createConfiguredContainer(key);
        container.start();
        return new ContainerSession() {
            @Override
            public ContainerExecResult execInContainer(String... command) throws Exception {
                Container.ExecResult execResult =
                        container.execInContainer(StandardCharsets.UTF_8, command);
                return new ContainerExecResult(
                        execResult.getExitCode(), execResult.getStdout(), execResult.getStderr());
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
