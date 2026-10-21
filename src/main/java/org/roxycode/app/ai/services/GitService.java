package org.roxycode.app.ai.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Service for read-only Git operations. */
@AgentService(
        value = "gitService",
        phases = {"*"})
@AgentDoc("Provides read-only access to Git repository information for the current project.")
public class GitService {
    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private final ProjectService projectService;

    public GitService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc(
            value = "Returns the name of the current Git branch.",
            examples = "gitService.getCurrentBranch()")
    public String getCurrentBranch() {
        return executeGitCommand("rev-parse", "--abbrev-ref", "HEAD");
    }

    @AgentDoc(value = "Returns the git status (porcelain).", examples = "gitService.getStatus()")
    public String getStatus() {
        return executeGitCommand("status", "--porcelain");
    }

    @AgentDoc(
            value = "Returns the git diff for unstaged changes.",
            examples = "gitService.getDiff()")
    public String getDiff() {
        return executeGitCommand("diff");
    }

    @AgentDoc(
            value = "Returns the git diff for a specific file.",
            examples = "gitService.getDiff('src/main/java/App.java')")
    public String getDiff(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return getDiff();
        }
        return executeGitCommand("diff", "--", relativePath.trim());
    }

    @AgentDoc(value = "Returns the git log (last 10 commits).", examples = "gitService.getLog()")
    public String getLog() {
        return getLog(10);
    }

    @AgentDoc(value = "Returns the git log.", examples = "gitService.getLog(5)")
    public String getLog(int limit) {
        return executeGitCommand("log", "-" + limit, "--oneline");
    }

    @AgentDoc(
            value = "Shows details for a specific commit.",
            examples = "gitService.showCommit('abcd123')")
    public String showCommit(String hash) {
        return executeGitCommand("show", hash);
    }

    private String executeGitCommand(String... args) {
        if (!projectService.hasActiveProject()) {
            return "No active project";
        }

        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));

            ProcessResult result =
                    ProcessRunner.run(
                            command,
                            projectService.getCurrentProjectRoot(),
                            Map.of("GIT_TERMINAL_PROMPT", "0"));

            if (result.timedOut()) {
                log.warn("Git command {} timed out", String.join(" ", args));
                return "Error executing git command: Command timed out";
            }

            int exitCode = result.exitCode();
            String out = result.output();
            if (exitCode != 0) {
                if (out.contains("not a git repository")) {
                    return "Not a git repository";
                }
                log.warn(
                        "Git command {} failed with exit code {}: {}",
                        String.join(" ", args),
                        exitCode,
                        out.trim());
                return "Error (" + exitCode + "): " + out.trim();
            }

            return out.trim();
        } catch (IOException e) {
            log.error(
                    "Exception executing git command {}: {}",
                    String.join(" ", args),
                    e.getMessage(),
                    e);
            return "Error executing git command: " + e.getMessage();
        }
    }
}
