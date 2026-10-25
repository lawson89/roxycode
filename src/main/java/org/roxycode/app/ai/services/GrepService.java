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

/** Service for searching text in the codebase using RipGrep. */
@AgentService(
        value = "grepService",
        phases = {"*"})
@AgentDoc(
        "Provides powerful text searching capabilities using RipGrep, respecting .gitignore and project boundaries.")
public class GrepService {
    private static final Logger log = LoggerFactory.getLogger(GrepService.class);
    static final int MAX_OUTPUT_LINES = 200;
    static final int MAX_CONTEXT_LINES = 20;
    private static final String DOC_REGEX_PATTERN = "The regex pattern to search for";
    private static final String DOC_RELATIVE_PATH =
            "The directory or file path relative to the project root";
    private static final String DOC_FILE_PATTERN =
            "Optional glob pattern for filtering files (e.g., '*.java')";

    private final ProjectService projectService;

    public GrepService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** Searches for a regex pattern in the project files using RipGrep. */
    @AgentDoc(
            value = "Searches for a regex pattern in the project files using RipGrep.",
            examples = "grepService.grep('TODO')")
    public EditorResult grep(@AgentDoc(DOC_REGEX_PATTERN) String pattern) {
        return grep(pattern, ".", null);
    }

    /** Searches for a regex pattern in the project files using RipGrep. */
    @AgentDoc(
            value = "Searches for a regex pattern in the project files using RipGrep.",
            examples = "grepService.grep('class GrepService', 'src')")
    public EditorResult grep(
            @AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath) {
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
    public EditorResult grep(
            @AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath,
            @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(pattern, relativePath, filePattern, 0, false, false);
    }

    /** Searches for a regex pattern with surrounding context lines before and after each match. */
    @AgentDoc(
            value =
                    "Searches for a regex pattern in the project files with surrounding context lines.",
            examples = "grepService.grepWithContext('@AgentDoc', 3)")
    public EditorResult grepWithContext(
            @AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc("Number of context lines before and after each match (0-20)")
                    int contextLines) {
        return grepWithContext(pattern, ".", null, contextLines);
    }

    /** Searches for a regex pattern with surrounding context lines before and after each match. */
    @AgentDoc(
            value =
                    "Searches for a regex pattern in the specified path with surrounding context lines.",
            examples = "grepService.grepWithContext('def process_', 'src', 3)")
    public EditorResult grepWithContext(
            @AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath,
            @AgentDoc("Number of context lines before and after each match (0-20)")
                    int contextLines) {
        return grepWithContext(pattern, relativePath, null, contextLines);
    }

    /** Searches for a regex pattern with surrounding context lines before and after each match. */
    @AgentDoc(
            value =
                    "Searches for a regex pattern in the specified path and file glob with surrounding context lines.",
            examples = "grepService.grepWithContext('public void', 'src', '*.java', 3)")
    public EditorResult grepWithContext(
            @AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath,
            @AgentDoc(DOC_FILE_PATTERN) String filePattern,
            @AgentDoc("Number of context lines before and after each match (0-20)")
                    int contextLines) {
        return executeRipGrep(pattern, relativePath, filePattern, contextLines, false, false);
    }

    /** Searches for a literal fixed string without regex interpretation. */
    @AgentDoc(
            value =
                    "Searches for a literal fixed string (no regex escaping needed) in the project files.",
            examples = "grepService.grepLiteral('std::vector<int>')")
    public EditorResult grepLiteral(@AgentDoc("The literal text to search for") String text) {
        return grepLiteral(text, ".", null);
    }

    /** Searches for a literal fixed string without regex interpretation. */
    @AgentDoc(
            value =
                    "Searches for a literal fixed string (no regex escaping needed) in the specified path.",
            examples = "grepService.grepLiteral('foo(bar)', 'src')")
    public EditorResult grepLiteral(
            @AgentDoc("The literal text to search for") String text,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath) {
        return grepLiteral(text, relativePath, null);
    }

    /** Searches for a literal fixed string without regex interpretation. */
    @AgentDoc(
            value =
                    "Searches for a literal fixed string (no regex escaping needed) in the specified path and file glob.",
            examples = "grepService.grepLiteral('List<String>', 'src', '*.java')")
    public EditorResult grepLiteral(
            @AgentDoc("The literal text to search for") String text,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath,
            @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(text, relativePath, filePattern, 0, true, false);
    }

    /** Searches for a regex pattern case-insensitively in the project files. */
    @AgentDoc(
            value = "Searches for a regex pattern case-insensitively in the project files.",
            examples = "grepService.grepIgnoreCase('fixme')")
    public EditorResult grepIgnoreCase(
            @AgentDoc("The regex pattern to search for case-insensitively") String pattern) {
        return grepIgnoreCase(pattern, ".", null);
    }

    /** Searches for a regex pattern case-insensitively in the specified path. */
    @AgentDoc(
            value = "Searches for a regex pattern case-insensitively in the specified path.",
            examples = "grepService.grepIgnoreCase('exception', 'src')")
    public EditorResult grepIgnoreCase(
            @AgentDoc("The regex pattern to search for case-insensitively") String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath) {
        return grepIgnoreCase(pattern, relativePath, null);
    }

    /** Searches for a regex pattern case-insensitively in the specified path and file glob. */
    @AgentDoc(
            value =
                    "Searches for a regex pattern case-insensitively in the specified path and file glob.",
            examples = "grepService.grepIgnoreCase('todo', 'src', '*.java')")
    public EditorResult grepIgnoreCase(
            @AgentDoc("The regex pattern to search for case-insensitively") String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath,
            @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(pattern, relativePath, filePattern, 0, false, true);
    }

    private EditorResult executeRipGrep(
            String pattern,
            String relativePath,
            String filePattern,
            int contextLines,
            boolean fixedStrings,
            boolean ignoreCase) {
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }
        if (pattern == null || pattern.isEmpty()) {
            return new EditorResult(false, "", "Search pattern must not be empty");
        }

        Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
        Path searchDir = projectService.resolvePath(relativePath);
        Path relSearchDir = root.relativize(searchDir);
        String targetArg = relSearchDir.toString().isEmpty() ? "." : relSearchDir.toString();

        int clampedContext = Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));

        List<String> command = new ArrayList<>();
        command.add("rg");
        if (clampedContext > 0) {
            command.add("-n");
            command.add("-C");
            command.add(String.valueOf(clampedContext));
        } else {
            command.add("--vimgrep");
        }
        command.add("--no-heading");
        command.add("--color=never");

        if (fixedStrings) {
            command.add("-F");
        }
        if (ignoreCase) {
            command.add("-i");
        }
        if (filePattern != null && !filePattern.isBlank()) {
            command.add("-g");
            command.add(filePattern);
        }

        command.add("--");
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

            String cappedOutput = capOutputLines(result.output().trim(), MAX_OUTPUT_LINES);
            return new EditorResult(true, cappedOutput, null);
        } catch (IOException e) {
            log.error("Exception executing RipGrep: {}", e.getMessage(), e);
            return new EditorResult(false, "", "Error executing RipGrep: " + e.getMessage());
        }
    }

    static String capOutputLines(String rawOutput, int maxLines) {
        if (rawOutput == null || rawOutput.isEmpty() || maxLines <= 0) {
            return rawOutput == null ? "" : rawOutput;
        }
        List<String> lines = rawOutput.lines().toList();
        if (lines.size() <= maxLines) {
            return rawOutput;
        }
        int omitted = lines.size() - maxLines;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) {
                sb.append((char) 10);
            }
            sb.append(lines.get(i));
        }
        sb.append((char) 10)
                .append("... (")
                .append(omitted)
                .append(
                        " more lines omitted; narrow your search with a more specific pattern, relativePath, or filePattern)");
        return sb.toString();
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
    public List<String> listFiles(
            @AgentDoc("The directory path relative to the project root") String relativePath) {
        if (!projectService.hasActiveProject()) {
            return new ArrayList<>();
        }

        Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
        Path searchDir = projectService.resolvePath(relativePath);
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
}
