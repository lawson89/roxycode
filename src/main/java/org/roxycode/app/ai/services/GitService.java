package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service for read-only Git operations.
 */
@Service
@AgentService("gitService")
@AgentDoc("Provides read-only access to Git repository information for the current project.")
public class GitService {
    private final ProjectService projectService;

    public GitService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc("Returns the name of the current Git branch.")
    public String getCurrentBranch() {
        return executeGitCommand("rev-parse", "--abbrev-ref", "HEAD");
    }

    @AgentDoc("Returns the git status (porcelain).")
    public String getStatus() {
        return executeGitCommand("status", "--porcelain");
    }

    @AgentDoc("Returns the git diff for unstaged changes.")
    public String getDiff() {
        return executeGitCommand("diff");
    }

    @AgentDoc("Returns the git log.")
    public String getLog(int limit) {
        return executeGitCommand("log", "-" + limit, "--oneline");
    }

    @AgentDoc("Shows details for a specific commit.")
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

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectService.getCurrentProjectRoot().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String out = output.toString();
                if (out.contains("not a git repository")) {
                    return "Not a git repository";
                }
                return "Error (" + exitCode + "): " + out.trim();
            }

            return output.toString().trim();
        } catch (IOException | InterruptedException e) {
            return "Error executing git command: " + e.getMessage();
        }
    }
}