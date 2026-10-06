package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for searching text in the codebase using RipGrep.
 */
@Service
@AgentService("grepService")
@AgentDoc("Provides powerful text searching capabilities using RipGrep, respecting .gitignore and project boundaries.")
public class GrepService {
    private static final Logger log = LoggerFactory.getLogger(GrepService.class);
    private final ProjectService projectService;

    public GrepService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * Searches for a regex pattern in the project files.
     *
     * @param pattern the regex pattern to search for
     * @param relativePath the starting path relative to the project root
     * @param filePattern optional glob pattern for files (e.g., "*.java")
     * @return the search results in an EditorResult
     */
    @AgentDoc(value = "Searches for a regex pattern in the project files using RipGrep.", 
              examples = {"grepService.grep('class GrepService', 'src', '*.java')"})
    public EditorResult grep(String pattern, String relativePath, String filePattern) {
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }

        Path root = projectService.getCurrentProjectRoot();
        Path searchDir = resolvePath(relativePath);

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
        command.add(searchDir.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(root.toFile());
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
            if (exitCode == 1) {
                return new EditorResult(true, "", null);
            } else if (exitCode != 0) {
                String error = output.toString().trim();
                log.warn("RipGrep failed with exit code {}: {}", exitCode, error);
                return new EditorResult(false, "", "RipGrep error (" + exitCode + "): " + error);
            }

            return new EditorResult(true, output.toString().trim(), null);
        } catch (IOException | InterruptedException e) {
            log.error("Exception executing RipGrep: {}", e.getMessage(), e);
            return new EditorResult(false, "", "Error executing RipGrep: " + e.getMessage());
        }
    }

    /**
     * Lists files in the project using RipGrep, respecting .gitignore.
     *
     * @param relativePath the starting path relative to the project root (null or empty for root)
     * @return a list of file paths relative to the project root
     */
    public List<String> listFiles(String relativePath) {
        if (!projectService.hasActiveProject()) {
            return new ArrayList<>();
        }

        Path root = projectService.getCurrentProjectRoot();
        Path searchDir = resolvePath(relativePath);

        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--files");
        command.add("--color=never");
        command.add(searchDir.toString());

        List<String> files = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(root.toFile());
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    files.add(line);
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
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
        Path resolved = root.resolve(relativePath != null ? relativePath : ".").toAbsolutePath().normalize();

        if (!resolved.startsWith(root)) {
            throw new SecurityException("Access denied: Path is outside project root.");
        }
        return resolved;
    }
}