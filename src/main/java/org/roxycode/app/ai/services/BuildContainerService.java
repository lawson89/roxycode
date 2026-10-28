package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.dockerjava.api.command.CreateContainerCmd;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
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
    private static final String TRUE_LITERAL = "true";
    private static final String FALSE_LITERAL = "false";
    private static final char BACKSLASH = 92;
    private static final char SQUOTE = 39;

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
            return scanContainerEnabledFallback(toml);
        }
    }

    private static boolean scanContainerEnabledFallback(String toml) {
        boolean inContainerSection = false;
        for (String line : toml.lines().toList()) {
            String sectionName = parseSectionHeaderName(line);
            if (sectionName != null) {
                inContainerSection = "container".equals(sectionName);
            } else if (inContainerSection) {
                Boolean enabledVal = parseEnabledAssignmentValue(line);
                if (enabledVal != null) {
                    return enabledVal;
                }
            }
        }
        return false;
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
            String sectionName = parseSectionHeaderName(line);
            if (sectionName != null) {
                inContainerSection = "container".equals(sectionName);
                if (inContainerSection && !foundContainerSection) {
                    foundContainerSection = true;
                    containerSectionIndex = i;
                }
                outputLines.add(line);
            } else if (inContainerSection && !updatedEnabledLine && isEnabledAssignment(line.trim())) {
                int eqIdx = line.indexOf('=');
                String rest = line.substring(eqIdx + 1);
                int commentIdx = findInlineCommentIndex(rest);
                String commentSuffix = commentIdx >= 0 ? " " + rest.substring(commentIdx) : "";
                outputLines.add(line.substring(0, eqIdx + 1) + " " + enabled + commentSuffix);
                updatedEnabledLine = true;
            } else {
                outputLines.add(line);
            }
        }

        if (!foundContainerSection) {
            String section = ("""
                    [container]
                    enabled = %s
                    image = '%s'
                    workdir = '%s'
                    """).formatted(enabled, DEFAULT_CONTAINER_IMAGE, DEFAULT_CONTAINER_WORKDIR);
            return safeToml.isEmpty() ? section : safeToml.stripTrailing() + NL + NL + section;
        }
        if (!updatedEnabledLine && containerSectionIndex >= 0) {
            outputLines.add(containerSectionIndex + 1, "enabled = " + enabled);
        }
        return String.join(NL, outputLines);
    }

    static String parseSectionHeaderName(String line) {
        if (line == null) {
            return null;
        }
        String withoutComment = stripInlineComment(line).trim();
        if (withoutComment.length() < 2 || withoutComment.charAt(0) != '['
                || withoutComment.charAt(withoutComment.length() - 1) != ']') {
            return null;
        }
        return withoutComment.substring(1, withoutComment.length() - 1).trim();
    }

    private static Boolean parseEnabledAssignmentValue(String line) {
        String trimmed = stripInlineComment(line).trim();
        if (!isEnabledAssignment(trimmed)) {
            return null;
        }
        int eqIdx = trimmed.indexOf('=');
        String val = trimmed.substring(eqIdx + 1).trim();
        if (TRUE_LITERAL.equals(val)) {
            return Boolean.TRUE;
        }
        if (FALSE_LITERAL.equals(val)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String stripInlineComment(String text) {
        int idx = findInlineCommentIndex(text);
        return idx >= 0 ? text.substring(0, idx) : text;
    }

    private static int findInlineCommentIndex(String text) {
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == BACKSLASH && inDoubleQuotes && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == SQUOTE && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (c == '#' && !inSingleQuotes && !inDoubleQuotes) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean isEnabledAssignment(String trimmed) {
        return trimmed.startsWith("enabled") && trimmed.substring("enabled".length()).trim().startsWith("=");
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
