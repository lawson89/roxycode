package org.roxycode.app.ai.services;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.YieldTurnException;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Service providing read-only file access to AI agents. */
@AgentService(
        value = "fileReadService",
        phases = {"*"})
@AgentDoc("Provides read-only access to files within the current project.")
public class FileReadService {

    private static final Logger log = LoggerFactory.getLogger(FileReadService.class);
    private final ProjectService projectService;

    public FileReadService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc(
            value =
                    "Checks if a file or directory exists at the given path relative to the project root.",
            examples = "fileReadService.fileExists('src/main/java/Main.java')")
    public boolean fileExists(String relativePath) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("File read operation cancelled");
        }
        Path path = projectService.resolvePath(relativePath);
        return Files.exists(path);
    }

    @AgentDoc(
            value = "Reads the content of a file at the given path relative to the project root.",
            examples = "fileReadService.readFile('pom.xml')")
    public String readFile(String relativePath) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("File read operation cancelled");
        }
        Path path = projectService.resolvePath(relativePath);
        if (!Files.isRegularFile(path)) {
            return "Error: Path is not a regular file or does not exist.";
        }
        try {
            return Files.readString(path);
        } catch (Exception e) {
            rethrowIfCancelled(e);
            log.error("Failed to read file {}: {}", relativePath, e.getMessage(), e);
            return "Error reading file: " + e.getMessage();
        }
    }

    @AgentDoc(
            value = "Lists the contents of the project root directory.",
            examples = "fileReadService.listDirectory()")
    public List<String> listDirectory() {
        return listDirectory(".");
    }

    @AgentDoc(
            value =
                    "Lists the contents of a directory at the given path relative to the project root.",
            examples = "fileReadService.listDirectory('src')")
    public List<String> listDirectory(String relativePath) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Directory listing cancelled");
        }
        Path path = projectService.resolvePath(relativePath);
        if (!Files.isDirectory(path)) {
            return Collections.singletonList("Error: Path is not a directory or does not exist.");
        }
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(path)) {
            List<String> entries = new ArrayList<>();
            for (Path entry : dirStream) {
                entries.add(entry.getFileName().toString());
            }
            return entries;
        } catch (Exception e) {
            rethrowIfCancelled(e);
            log.error("Failed to list directory {}: {}", relativePath, e.getMessage(), e);
            return Collections.singletonList("Error listing directory: " + e.getMessage());
        }
    }

    private void rethrowIfCancelled(Exception e) {
        if (e instanceof CancellationException
                || e instanceof ClosedByInterruptException
                || YieldTurnException.findCause(e, CancellationException.class) != null
                || YieldTurnException.findCause(e, ClosedByInterruptException.class) != null
                || YieldTurnException.findCause(e, InterruptedException.class) != null
                || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            if (e instanceof CancellationException ce) {
                throw ce;
            }
            CancellationException cancelEx =
                    new CancellationException("File read operation cancelled");
            cancelEx.initCause(e);
            throw cancelEx;
        }
    }
}
