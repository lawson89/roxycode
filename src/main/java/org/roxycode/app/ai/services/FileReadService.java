package org.roxycode.app.ai.services;

import java.io.IOException;
import java.io.InputStream;
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

    private static final String DOC_FILE_PATH = "The file path relative to the project root";
    static final int BINARY_SNIFF_BYTES = 8000;

    private final ProjectService projectService;

    public FileReadService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * Checks whether the given file appears to be a binary file by sniffing its initial bytes for a
     * NUL byte ({@code 0x00}).
     *
     * @param path the path to check
     * @return {@code true} if the file contains a NUL byte in the first {@value
     *     #BINARY_SNIFF_BYTES} bytes, {@code false} otherwise
     * @throws IOException if an I/O error occurs reading the file
     */
    public static boolean isBinaryFile(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[BINARY_SNIFF_BYTES];
            int read = in.read(buf);
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    @AgentDoc(
            value =
                    "Checks if a file or directory exists at the given path relative to the project root.",
            examples = "fileReadService.fileExists('src/main/java/Main.java')")
    public boolean fileExists(
            @AgentDoc("The file or directory path relative to the project root")
                    String relativePath) {
        Path path = projectService.resolvePath(relativePath);
        return Files.exists(path);
    }

    @AgentDoc(
            value = "Reads the content of a file at the given path relative to the project root.",
            examples = "fileReadService.readFile('pom.xml')")
    public String readFile(@AgentDoc(DOC_FILE_PATH) String relativePath) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "Error: Path is not a regular file or does not exist: " + relativePath);
        }
        return Files.readString(path);
    }

    @AgentDoc(
            value =
                    "Reads a 1-based inclusive range of lines from a file, prefixed with line numbers.",
            examples = "fileReadService.readLines('src/main/java/Main.java', 1, 50)")
    public String readLines(
            @AgentDoc(DOC_FILE_PATH) String relativePath,
            @AgentDoc("The 1-based start line number (inclusive)") int startLine,
            @AgentDoc("The 1-based end line number (inclusive)") int endLine)
            throws IOException {
        String content = readFile(relativePath);
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException(
                    "Error: Invalid line range: "
                            + startLine
                            + " to "
                            + endLine
                            + " (startLine must be >= 1 and endLine >= startLine)");
        }
        if (content.isEmpty()) {
            return "";
        }
        List<String> lines = content.lines().toList();
        if (startLine > lines.size()) {
            return "";
        }
        int clampedEnd = Math.min(endLine, lines.size());
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= clampedEnd; i++) {
            if (i > startLine) {
                sb.append((char) 10);
            }
            sb.append(i).append(": ").append(lines.get(i - 1));
        }
        return sb.toString();
    }

    @AgentDoc(
            value =
                    "Reads the entire file with every line prefixed by its 1-based line number ('<line>: <content>').",
            examples = "fileReadService.readFileNumbered('src/main/java/Main.java')")
    public String readFileNumbered(@AgentDoc(DOC_FILE_PATH) String relativePath)
            throws IOException {
        String content = readFile(relativePath);
        if (content.isEmpty()) {
            return "";
        }
        List<String> lines = content.lines().toList();
        return readLines(relativePath, 1, lines.size());
    }

    @AgentDoc(
            value = "Returns the total number of lines in a file.",
            examples = "fileReadService.getLineCount('src/main/java/Main.java')")
    public int getLineCount(@AgentDoc(DOC_FILE_PATH) String relativePath) throws IOException {
        String content = readFile(relativePath);
        return content.lines().toList().size();
    }

    @AgentDoc(
            value =
                    "Lists the contents of the project root directory (subdirectories end with '/').",
            examples = "fileReadService.listDirectory()")
    public List<String> listDirectory() throws IOException {
        return listDirectory(".");
    }

    @AgentDoc(
            value =
                    "Lists the contents of a directory at the given path relative to the project root (subdirectories end with '/').",
            examples = "fileReadService.listDirectory('src')")
    public List<String> listDirectory(
            @AgentDoc("The directory path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                    "Error: Path is not a directory or does not exist: " + relativePath);
        }
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(path)) {
            List<String> entries = new ArrayList<>();
            for (Path entry : dirStream) {
                String name = entry.getFileName().toString();
                entries.add(Files.isDirectory(entry) ? name + "/" : name);
            }
            Collections.sort(entries);
            return entries;
        }
    }
}
