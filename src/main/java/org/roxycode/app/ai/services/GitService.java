package org.roxycode.app.ai.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Service for read-only Git operations. */
@Service
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

  @AgentDoc(value = "Returns the git diff for unstaged changes.", examples = "gitService.getDiff()")
  public String getDiff() {
    return executeGitCommand("diff");
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

      ProcessBuilder pb = new ProcessBuilder(command);
      pb.directory(projectService.getCurrentProjectRoot().toFile());
      pb.redirectErrorStream(true);
      Process process = pb.start();

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line = reader.readLine();
        while (line != null) {
          output.append(line).append("\n");
          line = reader.readLine();
        }
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        String out = output.toString();
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

      return output.toString().trim();
    } catch (IOException e) {
      log.error(
          "Exception executing git command {}: {}", String.join(" ", args), e.getMessage(), e);
      return "Error executing git command: " + e.getMessage();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      CancellationException ce = new CancellationException("Git command interrupted");
      ce.initCause(e);
      throw ce;
    }
  }
}