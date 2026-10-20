package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;

/** Service providing read-only file access to AI agents. */
@AgentService(
        value = "fileReadService",
        phases = {"*"})
@AgentDoc("Provides read-only access to files within the current project.")
public class FileReadService {

    private final ProjectService projectService;

    public FileReadService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc(
            value =
                    "Checks if a file or directory exists at the given path relative to the project root.",
            examples = "fileReadService.fileExists('src/main/java/Main.java')")
    public boolean fileExists(String relativePath) {
        Path path = projectService.resolvePath(relativePath);
        return Files.exists(path);
    }

    @AgentDoc(
            value = "Reads the content of a file at the given path relative to the project root.",
            examples = "fileReadService.readFile('pom.xml')")
    public String readFile(String relativePath) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (!Files.isRegularFile(path)) {
            return "Error: Path is not a regular file or does not exist.";
        }
        return Files.readString(path);
    }

    @AgentDoc(
            value = "Lists the contents of the project root directory.",
            examples = "fileReadService.listDirectory()")
    public List<String> listDirectory() throws IOException {
        return listDirectory(".");
    }

    @AgentDoc(
            value =
                    "Lists the contents of a directory at the given path relative to the project root.",
            examples = "fileReadService.listDirectory('src')")
    public List<String> listDirectory(String relativePath) throws IOException {
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
        }
    }
}
