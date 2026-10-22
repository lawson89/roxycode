package org.roxycode.app.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.testcontainers.DockerClientFactory;

@Service
public class SystemToolService {
    private static final Logger log = LoggerFactory.getLogger(SystemToolService.class);
    private static final String JAVA = "Java";
    private static final String DOCKER = "Docker";
    private static final String ARG_VERSION = "--version";
    static final String STATUS_DETECTED = "Detected";
    static final String STATUS_ERROR = "Error";
    static final String STATUS_MISSING = "Missing";
    static final String VERSION_NOT_FOUND = "Not found";
    private static final int MAX_DOCKER_ERROR_LENGTH = 120;
    private static final String DOCKER_DESCRIPTION =
            "Required for isolated build and test execution via Testcontainers.";

    public record DockerVersionInfo(String version, String apiVersion) {}

    @FunctionalInterface
    interface DockerProbe {
        DockerVersionInfo probe() throws Exception;
    }

    private DockerProbe dockerProbe;
    private BooleanSupplier dockerCliPresentSupplier;

    public SystemToolService() {
        this(SystemToolService::probeWithTestcontainers, SystemToolService::isDockerCliInstalled);
    }

    SystemToolService(DockerProbe dockerProbe, BooleanSupplier dockerCliPresentSupplier) {
        this.dockerProbe = dockerProbe;
        this.dockerCliPresentSupplier = dockerCliPresentSupplier;
    }

    void setDockerProbe(DockerProbe dockerProbe) {
        this.dockerProbe = dockerProbe;
    }

    void setDockerCliPresentSupplier(BooleanSupplier dockerCliPresentSupplier) {
        this.dockerCliPresentSupplier = dockerCliPresentSupplier;
    }

    @SuppressWarnings("PMD.CloseResource")
    static DockerVersionInfo probeWithTestcontainers() {
        DockerClient client = DockerClientFactory.instance().client();
        Version version = client.versionCmd().exec();
        return new DockerVersionInfo(
                version != null ? version.getVersion() : null,
                version != null ? version.getApiVersion() : null);
    }

    static boolean isDockerCliInstalled() {
        try {
            ProcessResult versionResult =
                    ProcessRunner.run(List.of("docker", ARG_VERSION), null, true);
            return !versionResult.timedOut() && versionResult.exitCode() == 0;
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    public CompletableFuture<List<SystemTool>> detectTools() {
        return CompletableFuture.supplyAsync(
                () -> {
                    List<SystemTool> tools = new ArrayList<>();
                    tools.add(
                            detectTool(
                                    "Ripgrep",
                                    "rg",
                                    ARG_VERSION,
                                    "Required for fast file searching."));
                    tools.add(
                            detectTool(
                                    "Git",
                                    "git",
                                    ARG_VERSION,
                                    "Required for version control integration."));
                    tools.add(
                            detectTool(
                                    "Universal Ctags",
                                    "ctags",
                                    ARG_VERSION,
                                    "Required for code navigation and symbol extraction."));
                    tools.add(
                            detectTool(
                                    JAVA,
                                    "java",
                                    "-version",
                                    "The runtime environment for this application."));
                    tools.add(detectDockerTool());
                    return tools;
                });
    }

    /** Asynchronously checks whether Docker is reachable and compatible with Testcontainers. */
    public CompletableFuture<Boolean> isDockerAvailable() {
        return CompletableFuture.supplyAsync(this::checkDockerAvailable);
    }

    /** Synchronously checks whether Docker is reachable and compatible with Testcontainers. */
    public boolean checkDockerAvailable() {
        return STATUS_DETECTED.equals(detectDockerTool().status());
    }

    SystemTool detectDockerTool() {
        try {
            DockerVersionInfo info = dockerProbe.probe();
            String serverVersion =
                    info != null && info.version() != null && !info.version().isBlank()
                            ? info.version().trim()
                            : "Unknown";
            String apiVersion =
                    info != null && info.apiVersion() != null && !info.apiVersion().isBlank()
                            ? info.apiVersion().trim()
                            : "";
            String versionDisplay =
                    !apiVersion.isEmpty()
                            ? "Docker " + serverVersion + " (API " + apiVersion + ")"
                            : "Docker " + serverVersion;
            return new SystemTool(DOCKER, versionDisplay, STATUS_DETECTED, DOCKER_DESCRIPTION);
        } catch (Throwable t) {
            ExceptionUtils.rethrowIfCancelled(t, "Docker detection cancelled");
            String rootMessage = extractDiagnosticMessage(t);
            log.warn("Testcontainers Docker detection failed: {}", rootMessage);
            if (isGenericEnvironmentNotFound(rootMessage)
                    && (dockerCliPresentSupplier == null
                            || !dockerCliPresentSupplier.getAsBoolean())) {
                return new SystemTool(
                        DOCKER, VERSION_NOT_FOUND, STATUS_MISSING, DOCKER_DESCRIPTION);
            }
            return new SystemTool(
                    DOCKER,
                    formatDockerErrorMessage(rootMessage),
                    STATUS_ERROR,
                    DOCKER_DESCRIPTION);
        }
    }

    private static String extractDiagnosticMessage(Throwable t) {
        Throwable root = ExceptionUtils.getRootCause(t);
        if (root != null && root.getMessage() != null && !root.getMessage().isBlank()) {
            return root.getMessage().trim();
        }
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
            return t.getMessage().trim();
        }
        return root != null ? root.getClass().getSimpleName() : t.getClass().getSimpleName();
    }

    private static boolean isGenericEnvironmentNotFound(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("could not find a valid docker environment")
                || lower.contains("previous attempts to find a docker environment failed")
                || lower.equals("not found");
    }

    private static String formatDockerErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "Testcontainers connection failed";
        }
        String normalized = rawMessage.replaceAll("\s+", " ").trim();
        if (normalized.length() > MAX_DOCKER_ERROR_LENGTH) {
            return normalized.substring(0, MAX_DOCKER_ERROR_LENGTH) + "...";
        }
        return normalized;
    }

    private SystemTool detectTool(
            String name, String command, String versionArg, String description) {
        try {
            ProcessResult result = ProcessRunner.run(List.of(command, versionArg), null, true);
            if (!result.timedOut() && result.exitCode() == 0) {
                String version = parseVersion(result.output(), name);
                return new SystemTool(name, version, STATUS_DETECTED, description);
            } else {
                log.warn("Tool {} detection failed with exit code {}", name, result.exitCode());
                return new SystemTool(name, VERSION_NOT_FOUND, STATUS_MISSING, description);
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Exception during tool {} detection: {}", name, e.getMessage());
            return new SystemTool(name, VERSION_NOT_FOUND, STATUS_MISSING, description);
        }
    }

    private String parseVersion(String output, String toolName) {
        if (output == null || output.isEmpty()) return "Unknown";
        String fullOutput = output.trim();
        if (JAVA.equals(toolName)) {
            return fullOutput.contains("version")
                    ? fullOutput.substring(fullOutput.indexOf("version"))
                    : fullOutput;
        }
        return fullOutput.length() > 50 ? fullOutput.substring(0, 50) + "..." : fullOutput;
    }
}
