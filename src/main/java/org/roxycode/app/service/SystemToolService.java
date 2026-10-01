package org.roxycode.app.service;

import org.roxycode.app.model.SystemTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SystemToolService {

    private static final Logger log = LoggerFactory.getLogger(SystemToolService.class);

    public CompletableFuture<List<SystemTool>> detectTools() {
        return CompletableFuture.supplyAsync(() -> {
            List<SystemTool> tools = new ArrayList<>();
            tools.add(detectTool("Ripgrep", "rg", "--version", "Required for fast file searching."));
            tools.add(detectTool("Git", "git", "--version", "Required for version control integration."));
            tools.add(detectTool("Universal Ctags", "ctags", "--version", "Required for code navigation and symbol extraction."));
            tools.add(detectTool("Java", "java", "-version", "The runtime environment for this application."));
            return tools;
        });
    }

    private SystemTool detectTool(String name, String command, String versionArg, String description) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, versionArg);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(" ");
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                String version = parseVersion(output.toString(), name);
                return new SystemTool(name, version, "Detected", description);
            } else {
                log.warn("Tool {} detection failed with exit code {}", name, exitCode);
                return new SystemTool(name, "Not found", "Missing", description);
            }
        } catch (Exception e) {
            log.error("Exception during tool {} detection: {}", name, e.getMessage());
            return new SystemTool(name, "Not found", "Missing", description);
        }
    }

    private String parseVersion(String output, String toolName) {
        if (output == null || output.isEmpty()) return "Unknown";
        String firstLine = output.split(" ")[0].trim();
        // Handle cases where the first word isn't the version
        String fullOutput = output.trim();
        if (toolName.equals("Java")) {
            return fullOutput.contains("version") ? fullOutput.substring(fullOutput.indexOf("version")) : fullOutput;
        }
        return fullOutput.length() > 50 ? fullOutput.substring(0, 50) + "..." : fullOutput;
    }
}