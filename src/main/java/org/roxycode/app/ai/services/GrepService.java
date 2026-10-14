package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Service for searching text in the codebase using RipGrep. */
@Service
@AgentService(
    value = "grepService",
    phases = {"*"})
@AgentDoc(
    "Provides powerful text searching capabilities using RipGrep, respecting .gitignore and project boundaries.")
public class GrepService {
  private static final Logger log = LoggerFactory.getLogger(GrepService.class);
  private final ProjectService projectService;

  public GrepService(ProjectService projectService) {
    this.projectService = projectService;
  }

  /** Searches for a regex pattern in the project files using RipGrep. */
  @AgentDoc(
      value = "Searches for a regex pattern in the project files using RipGrep.",
      examples = "grepService.grep('TODO')")
  public EditorResult grep(String pattern) {
    return grep(pattern, ".", null);
  }

  /** Searches for a regex pattern in the project files using RipGrep. */
  @AgentDoc(
      value = "Searches for a regex pattern in the project files using RipGrep.",
      examples = "grepService.grep('class GrepService', 'src')")
  public EditorResult grep(String pattern, String relativePath) {
    return grep(pattern, relativePath, null);
  }

  /**
   * Searches for a regex pattern in the project files using RipGrep.
   *
   * @param pattern the regex pattern to search for
   * @param relativePath the starting path relative to the project root
   * @param filePattern optional glob pattern for files (e.g., "*.java")
   * @return the search results in an EditorResult
   */
  @AgentDoc(
      value = "Searches for a regex pattern in the project files using RipGrep.",
      examples = "grepService.grep('import', 'src', '*.java')")
  public EditorResult grep(String pattern, String relativePath, String filePattern) {
    if (!projectService.hasActiveProject()) {
      return new EditorResult(false, "", "No active project");
    }

    Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
    Path searchDir = resolvePath(relativePath);
    Path relSearchDir = root.relativize(searchDir);
    String targetArg = relSearchDir.toString().isEmpty() ? "." : relSearchDir.toString();

    List<String> command = new ArrayList<>();
    command.add("rg");
    command.add("--vimgrep");
    command.add("--no-heading");
    command.add("--color=never");

    if (filePattern != null && !filePattern.isBlank()) {
      command.add("-g");
      command.add(filePattern);
    }

    command.add(pattern);
    command.add(targetArg);

    try {
      ProcessResult result = ProcessRunner.run(command, root, true);
      if (result.timedOut()) {
        log.warn("RipGrep timed out for pattern: {}", pattern);
        return new EditorResult(false, "", "RipGrep timed out");
      }

      int exitCode = result.exitCode();
      final int exitCodeNoMatches = 1;
      if (exitCode == exitCodeNoMatches) {
        return new EditorResult(true, "", null);
      } else if (exitCode != 0) {
        String error = result.output().trim();
        log.warn("RipGrep failed with exit code {}: {}", exitCode, error);
        return new EditorResult(false, "", "RipGrep error (" + exitCode + "): " + error);
      }

      return new EditorResult(true, result.output().trim(), null);
    } catch (IOException e) {
      log.error("Exception executing RipGrep: {}", e.getMessage(), e);
      return new EditorResult(false, "", "Error executing RipGrep: " + e.getMessage());
    }
  }

  /** Lists files in the project using RipGrep, respecting .gitignore. */
  @AgentDoc(
      value = "Lists files in the project root using RipGrep, respecting .gitignore.",
      examples = "grepService.listFiles()")
  public List<String> listFiles() {
    return listFiles(".");
  }

  /**
   * Lists files in the project using RipGrep, respecting .gitignore.
   *
   * @param relativePath the starting path relative to the project root (null or empty for root)
   * @return a list of file paths relative to the project root
   */
  @AgentDoc(
      value = "Lists files in a specific directory using RipGrep, respecting .gitignore.",
      examples = "grepService.listFiles('src/main')")
  public List<String> listFiles(String relativePath) {
    if (!projectService.hasActiveProject()) {
      return new ArrayList<>();
    }

    Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
    Path searchDir = resolvePath(relativePath);
    Path relSearchDir = root.relativize(searchDir);

    List<String> command = new ArrayList<>();
    command.add("rg");
    command.add("--files");
    command.add("--color=never");
    if (!relSearchDir.toString().isEmpty()) {
      command.add(relSearchDir.toString());
    }

    List<String> files = new ArrayList<>();
    try {
      ProcessResult result = ProcessRunner.run(command, root, false);
      if (result.timedOut()) {
        log.warn("RipGrep listFiles timed out in {}", root);
        return files;
      }
      String output = result.output();
      if (output != null && !output.isEmpty()) {
        for (String line : output.lines().toList()) {
          if (!line.isBlank()) {
            String normalized = line.replace((char) 92, '/');
            if (normalized.startsWith("./")) {
              normalized = normalized.substring(2);
            }
            files.add(normalized);
          }
        }
      }
    } catch (IOException e) {
      log.error("Failed to list files using RipGrep: {}", e.getMessage());
    }
    return files;
  }

  private Path resolvePath(String relativePath) {
    Path projectRoot = projectService.getCurrentProjectRoot();
    if (projectRoot == null) {
      throw new IllegalStateException("Project root is not set.");
    }

    Path root = projectRoot.toAbsolutePath().normalize();
    Path resolved =
        root.resolve(relativePath != null ? relativePath : ".").toAbsolutePath().normalize();

    if (!resolved.startsWith(root)) {
      throw new SecurityException("Access denied: Path is outside project root.");
    }
    return resolved;
  }
}
