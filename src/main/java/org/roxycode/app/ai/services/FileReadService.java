package org.roxycode.app.ai.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.ExceptionUtils;

@AgentService(value = "fileReadService", phases = {"*"})
@AgentDoc("Provides read-only access to files within the current project and mounted @<alias> reference folders.")
public class FileReadService {

    private static final String DOC_FILE_PATH = "The file path relative to the project root (or '@<alias>/path' for a read-only reference folder)";
    private static final String DOC_FILE_PATHS = "Array of file paths relative to the project root (or '@<alias>/path' for read-only reference folders)";
    private static final String ERROR_PREFIX = "Error: ";
    static final int BINARY_SNIFF_BYTES = 8000;
    static final int MAX_SINGLE_FILE_CHARS = 100_000;
    static final String SINGLE_FILE_TRUNCATED_SUFFIX = "\n... [Truncated at " + MAX_SINGLE_FILE_CHARS
            + " chars. Use symbolService.readSymbol(path, symbolName), fileReadService.readLines(path, startLine, endLine), or grepService to inspect specific sections.]";
    static final int MAX_BATCH_FILES = 25;
    static final int MAX_BATCH_CHARS = 120_000;
    static final String TRUNCATED_KEY = "_truncated";

    private final ProjectService projectService;

    public FileReadService(ProjectService projectService) {
        this.projectService = projectService;
    }

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

    @AgentDoc(value = "Checks if a file or directory exists at the given path relative to the project root or a mounted @<alias> reference folder.", examples = {
            "fileReadService.fileExists('src/main/java/Main.java')",
            "fileReadService.fileExists('@billing-service/src/main/java/Invoice.java')"})
    public boolean fileExists(
            @AgentDoc("The file or directory path relative to the project root (or '@<alias>/path')") String relativePath) {
        return Files.exists(projectService.resolveReadPath(relativePath));
    }

    @AgentDoc(value = "Reads the content of a file at the given path relative to the project root.", examples = "fileReadService.readFile('pom.xml')")
    /**
     * Reads the entire content of a file.
     * 
     * @param relativePath
     *            the file path relative to the project root
     * @return the file content
     * @throws IOException
     *             if an I/O error occurs
     */
    public String readFile(@AgentDoc(DOC_FILE_PATH) String relativePath) throws IOException {
        return truncateSingleFileContent(readRawTextFile(relativePath));
    }

    private String readRawTextFile(String relativePath) throws IOException {
        Path path = projectService.resolveReadPath(relativePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(ERROR_PREFIX + "File does not exist: " + relativePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(ERROR_PREFIX + "Path is not a regular file: " + relativePath);
        }
        if (isBinaryFile(path)) {
            throw new IllegalArgumentException(ERROR_PREFIX + "Binary file cannot be read as text: " + relativePath);
        }
        return Files.readString(path);
    }

    private static String truncateSingleFileContent(String content) {
        if (content == null || content.length() <= MAX_SINGLE_FILE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_SINGLE_FILE_CHARS) + SINGLE_FILE_TRUNCATED_SUFFIX;
    }

    @AgentDoc(value = "Reads multiple files in a single batch call, returning a map of relativePath to file content (or an 'Error: ...' message for missing, binary, or invalid files).", examples = "fileReadService.readFiles(['pom.xml', 'src/main/java/Main.java'])")
    public Map<String, String> readFiles(@AgentDoc(DOC_FILE_PATHS) String... relativePaths) {
        return readBatch(Arrays.asList(ArrayUtils.nullToEmpty(relativePaths)), false);
    }

    Map<String, String> readFiles(List<String> relativePaths) {
        return readBatch(relativePaths, false);
    }

    @AgentDoc(value = "Reads multiple files with 1-based line numbers ('<line>: <content>') in a single batch call, returning a map of relativePath to numbered content (or an 'Error: ...' message for missing, binary, or invalid files).", examples = "fileReadService.readFilesNumbered(['src/main/java/Main.java', 'src/main/java/App.java'])")
    public Map<String, String> readFilesNumbered(@AgentDoc(DOC_FILE_PATHS) String... relativePaths) {
        return readBatch(Arrays.asList(ArrayUtils.nullToEmpty(relativePaths)), true);
    }

    Map<String, String> readFilesNumbered(List<String> relativePaths) {
        return readBatch(relativePaths, true);
    }

    private Map<String, String> readBatch(List<String> relativePaths, boolean numbered) {
        Map<String, String> results = new LinkedHashMap<>();
        if (relativePaths == null || relativePaths.isEmpty()) {
            return results;
        }
        int filesProcessed = 0;
        int totalChars = 0;
        for (int i = 0; i < relativePaths.size(); i++) {
            String relativePath = relativePaths.get(i);
            if (relativePath == null || relativePath.isBlank()) {
                continue;
            }
            if (results.containsKey(relativePath)) {
                continue;
            }
            if (filesProcessed >= MAX_BATCH_FILES) {
                int omitted = relativePaths.size() - i;
                results.put(TRUNCATED_KEY, "Batch file limit (" + MAX_BATCH_FILES + " files) reached; " + omitted
                        + " remaining file(s) omitted.");
                break;
            }
            filesProcessed++;
            String entryContent = readSingleFileForBatch(relativePath, numbered);
            if (totalChars + entryContent.length() > MAX_BATCH_CHARS) {
                int remainingBudget = MAX_BATCH_CHARS - totalChars;
                if (remainingBudget > 0) {
                    results.put(relativePath, entryContent.substring(0, remainingBudget) + '\n'
                            + "... [Truncated at batch character limit]");
                }
                results.put(TRUNCATED_KEY,
                        "Batch character limit (" + MAX_BATCH_CHARS + " chars) reached; remaining content omitted.");
                break;
            }
            totalChars += entryContent.length();
            results.put(relativePath, entryContent);
        }
        return results;
    }

    private String readSingleFileForBatch(String relativePath, boolean numbered) {
        try {
            if (!numbered) {
                return readRawTextFile(relativePath);
            }
            return readLines(relativePath, 1, Integer.MAX_VALUE);
        } catch (Exception e) {
            return StringUtils.prependIfMissing(ExceptionUtils.getMessageOrSimpleName(e), ERROR_PREFIX);
        }
    }

    static String formatNumberedLines(List<String> lines, int startLine, int endLine) {
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Error: Invalid line range: " + startLine + " to " + endLine
                    + " (startLine must be >= 1 and endLine >= startLine)");
        }
        if (lines == null || lines.isEmpty() || startLine > lines.size()) {
            return "";
        }
        int clampedEnd = Math.min(endLine, lines.size());
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= clampedEnd; i++) {
            if (i > startLine) {
                sb.append('\n');
            }
            sb.append(i).append(": ").append(lines.get(i - 1));
        }
        return sb.toString();
    }

    @AgentDoc(value = "Reads a 1-based inclusive range of lines from a file, prefixed with line numbers.", examples = "fileReadService.readLines('src/main/java/Main.java', 1, 50)")
    public String readLines(@AgentDoc(DOC_FILE_PATH) String relativePath,
            @AgentDoc("The 1-based start line number (inclusive)") int startLine,
            @AgentDoc("The 1-based end line number (inclusive)") int endLine) throws IOException {
        String content = readRawTextFile(relativePath);
        return formatNumberedLines(content.lines().toList(), startLine, endLine);
    }

    @AgentDoc(value = "Reads the entire file with every line prefixed by its 1-based line number ('<line>: <content>').", examples = "fileReadService.readFileNumbered('src/main/java/Main.java')")
    public String readFileNumbered(@AgentDoc(DOC_FILE_PATH) String relativePath) throws IOException {
        return truncateSingleFileContent(readLines(relativePath, 1, Integer.MAX_VALUE));
    }

    @AgentDoc(value = "Returns the total number of lines in a file.", examples = "fileReadService.getLineCount('src/main/java/Main.java')")
    public int getLineCount(@AgentDoc(DOC_FILE_PATH) String relativePath) throws IOException {
        return (int) readRawTextFile(relativePath).lines().count();
    }

    @AgentDoc(value = "Lists the contents of the project root directory (subdirectories end with '/').", examples = "fileReadService.listDirectory()")
    public List<String> listDirectory() throws IOException {
        return listDirectory(".");
    }

    @AgentDoc(value = "Lists the contents of a directory at the given path relative to the project root or a mounted @<alias> reference folder (subdirectories end with '/').", examples = {
            "fileReadService.listDirectory('src')", "fileReadService.listDirectory('@billing-service/src')"})
    public List<String> listDirectory(
            @AgentDoc("The directory path relative to the project root (or '@<alias>' / '@<alias>/subpath')") String relativePath)
            throws IOException {
        Path path = projectService.resolveReadPath(relativePath);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Error: Path is not a directory or does not exist: " + relativePath);
        }
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(path)) {
            List<String> entries = new ArrayList<>();
            for (Path entry : dirStream) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    entries.add(name + "/");
                } else {
                    entries.add(name);
                }
            }
            Collections.sort(entries);
            return entries;
        }
    }
}
