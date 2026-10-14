package org.roxycode.app.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemToolService {
  private static final Logger log = LoggerFactory.getLogger(SystemToolService.class);
  private static final String JAVA = "Java";

  public CompletableFuture<List<SystemTool>> detectTools() {
    return CompletableFuture.supplyAsync(
        () -> {
          List<SystemTool> tools = new ArrayList<>();
          tools.add(detectTool("Ripgrep", "rg", "--version", "Required for fast file searching."));
          tools.add(
              detectTool("Git", "git", "--version", "Required for version control integration."));
          tools.add(
              detectTool(
                  "Universal Ctags",
                  "ctags",
                  "--version",
                  "Required for code navigation and symbol extraction."));
          tools.add(
              detectTool(
                  JAVA, "java", "-version", "The runtime environment for this application."));
          return tools;
        });
  }

  private SystemTool detectTool(
      String name, String command, String versionArg, String description) {
    try {
      ProcessResult result = ProcessRunner.run(List.of(command, versionArg), null, true);
      if (!result.timedOut() && result.exitCode() == 0) {
        String version = parseVersion(result.output(), name);
        return new SystemTool(name, version, "Detected", description);
      } else {
        log.warn("Tool {} detection failed with exit code {}", name, result.exitCode());
        return new SystemTool(name, "Not found", "Missing", description);
      }
    } catch (CancellationException e) {
      throw e;
    } catch (Exception e) {
      log.error("Exception during tool {} detection: {}", name, e.getMessage());
      return new SystemTool(name, "Not found", "Missing", description);
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
