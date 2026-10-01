package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service providing read-only file access to AI agents.
 */
@AgentService(value = "fileReadService", roles = {"*"})
@AgentDoc("Provides read-only access to files within the current project.")
public class FileReadService {

    private static final Logger log = LoggerFactory.getLogger(FileReadService.class);
    private final ProjectService projectService;

    public FileReadService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc("Checks if a file or directory exists at the given path relative to the project root.")
    public boolean fileExists(String relativePath) {
        Path path = resolvePath(relativePath);
        return Files.exists(path);
    }

    @AgentDoc("Reads the content of a file at the given path relative to the project root.")
    public String readFile(String relativePath) {
        Path path = resolvePath(relativePath);
        if (!Files.isRegularFile(path)) {
            return "Error: Path is not a regular file or does not exist.";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Failed to read file {}: {}", relativePath, e.getMessage(), e);
            return "Error reading file: " + e.getMessage();
        }
    }

    @AgentDoc("Lists the contents of a directory at the given path relative to the project root.")
    public List<String> listDirectory(String relativePath) {
        Path path = resolvePath(relativePath);
        if (!Files.isDirectory(path)) {
            return Collections.singletonList("Error: Path is not a directory or does not exist.");
        }
        try (Stream<Path> stream = Files.list(path)) {
            return stream.map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list directory {}: {}", relativePath, e.getMessage(), e);
            return Collections.singletonList("Error listing directory: " + e.getMessage());
        }
    }

    private Path resolvePath(String relativePath) {
        Path projectRoot = projectService.getCurrentProjectRoot();
        if (projectRoot == null) {
             throw new IllegalStateException("Project root is not set.");
        }
        
        Path root = projectRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).toAbsolutePath().normalize();

        if (!resolved.startsWith(root)) {
            throw new SecurityException("Access denied: Path is outside project root.");
        }
        return resolved;
    }
}