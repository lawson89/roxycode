package org.roxycode.app.ai.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AgentService(value = "grepService", phases = {"*"})
@AgentDoc("Provides powerful text searching capabilities using RipGrep, respecting .gitignore and project boundaries.")
public class GrepService {
    private static final Logger log = LoggerFactory.getLogger(GrepService.class);
    static final int MAX_OUTPUT_LINES = 200;
    static final int MAX_CONTEXT_LINES = 20;
    private static final String DOC_REGEX_PATTERN = "The regex pattern to search for";
    private static final String DOC_RELATIVE_PATH = "The directory or file path relative to the project root";
    private static final String DOC_FILE_PATTERN = "Optional glob pattern for filtering files (e.g., '*.java')";
    private static final String DOC_CONTEXT_LINES = "Number of context lines before and after each match (0-20)";
    private static final String DOC_LITERAL_TEXT = "The literal text to search for";
    private static final String DOC_IGNORE_CASE_PATTERN = "The regex pattern to search for case-insensitively";
    private static final char BACKSLASH = '\\';
    private static final char LF = '\n';

    private final ProjectService projectService;

    public GrepService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files using RipGrep.", examples = "grepService.grep('TODO')")
    public EditorResult grep(@AgentDoc(DOC_REGEX_PATTERN) String pattern) {
        return grep(pattern, ".", null);
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files using RipGrep.", examples = "grepService.grep('class GrepService', 'src')")
    public EditorResult grep(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath) {
        return grep(pattern, relativePath, null);
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files using RipGrep.", examples = "grepService.grep('import', 'src', '*.java')")
    public EditorResult grep(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(pattern, relativePath, filePattern, 0, false, false);
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files with surrounding context lines.", examples = "grepService.grepWithContext('@AgentDoc', 3)")
    public EditorResult grepWithContext(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_CONTEXT_LINES) int contextLines) {
        return grepWithContext(pattern, ".", null, contextLines);
    }

    @AgentDoc(value = "Searches for a regex pattern in the specified path with surrounding context lines.", examples = "grepService.grepWithContext('def process_', 'src', 3)")
    public EditorResult grepWithContext(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_CONTEXT_LINES) int contextLines) {
        return grepWithContext(pattern, relativePath, null, contextLines);
    }

    @AgentDoc(value = "Searches for a regex pattern in the specified path and file glob with surrounding context lines.", examples = "grepService.grepWithContext('public void', 'src', '*.java', 3)")
    public EditorResult grepWithContext(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern,
            @AgentDoc(DOC_CONTEXT_LINES) int contextLines) {
        return executeRipGrep(pattern, relativePath, filePattern, contextLines, false, false);
    }

    @AgentDoc(value = "Searches for a literal fixed string (no regex escaping needed) in the project files.", examples = "grepService.grepLiteral('std::vector<int>')")
    public EditorResult grepLiteral(@AgentDoc(DOC_LITERAL_TEXT) String text) {
        return grepLiteral(text, ".", null);
    }

    @AgentDoc(value = "Searches for a literal fixed string (no regex escaping needed) in the specified path.", examples = "grepService.grepLiteral('foo(bar)', 'src')")
    public EditorResult grepLiteral(@AgentDoc(DOC_LITERAL_TEXT) String text,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath) {
        return grepLiteral(text, relativePath, null);
    }

    @AgentDoc(value = "Searches for a literal fixed string (no regex escaping needed) in the specified path and file glob.", examples = "grepService.grepLiteral('List<String>', 'src', '*.java')")
    public EditorResult grepLiteral(@AgentDoc(DOC_LITERAL_TEXT) String text,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(text, relativePath, filePattern, 0, true, false);
    }

    @AgentDoc(value = "Searches for a regex pattern case-insensitively in the project files.", examples = "grepService.grepIgnoreCase('fixme')")
    public EditorResult grepIgnoreCase(@AgentDoc(DOC_IGNORE_CASE_PATTERN) String pattern) {
        return grepIgnoreCase(pattern, ".", null);
    }

    @AgentDoc(value = "Searches for a regex pattern case-insensitively in the specified path.", examples = "grepService.grepIgnoreCase('exception', 'src')")
    public EditorResult grepIgnoreCase(@AgentDoc(DOC_IGNORE_CASE_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath) {
        return grepIgnoreCase(pattern, relativePath, null);
    }

    @AgentDoc(value = "Searches for a regex pattern case-insensitively in the specified path and file glob.", examples = "grepService.grepIgnoreCase('todo', 'src', '*.java')")
    public EditorResult grepIgnoreCase(@AgentDoc(DOC_IGNORE_CASE_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(pattern, relativePath, filePattern, 0, false, true);
    }

    private EditorResult executeRipGrep(String pattern, String relativePath, String filePattern, int contextLines,
            boolean fixedStrings, boolean ignoreCase) {
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }
        if (pattern == null || pattern.isEmpty()) {
            return new EditorResult(false, "", "Search pattern must not be empty");
        }

        Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
        String targetArg = projectService.resolveRelativePath(relativePath);
        int clampedContext = Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));

        List<String> command = new ArrayList<>();
        command.add("rg");
        if (clampedContext > 0) {
            command.addAll(List.of("-n", "-C", String.valueOf(clampedContext)));
        } else {
            command.add("--vimgrep");
        }
        command.addAll(List.of("--no-heading", "--color=never"));

        if (fixedStrings)
            command.add("-F");
        if (ignoreCase)
            command.add("-i");
        if (filePattern != null && !filePattern.isBlank()) {
            command.addAll(List.of("-g", filePattern));
        }
        command.addAll(List.of("--", pattern, targetArg));

        try {
            ProcessResult result = ProcessRunner.run(command, root, true);
            if (result.timedOut()) {
                log.warn("RipGrep timed out for pattern: {}", pattern);
                return new EditorResult(false, "", "RipGrep timed out");
            }
            int exitCode = result.exitCode();
            if (exitCode == 1) {
                return new EditorResult(true, "", null);
            } else if (exitCode != 0) {
                String error = result.output().trim();
                log.warn("RipGrep failed with exit code {}: {}", exitCode, error);
                return new EditorResult(false, "", "RipGrep error (" + exitCode + "): " + error);
            }
            return new EditorResult(true,
                    capOutputLines(stripRootPrefix(result.output().trim(), root), MAX_OUTPUT_LINES), null);
        } catch (IOException e) {
            log.warn("Failed to execute ripgrep: {}", e.getMessage());
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
        return String.join(String.valueOf(LF), lines.subList(0, maxLines)) + LF + "... (" + (lines.size() - maxLines)
                + " more lines omitted; narrow your search with a more specific pattern, relativePath, or filePattern)";
    }

    @AgentDoc(value = "Lists files in the project root using RipGrep, respecting .gitignore.", examples = "grepService.listFiles()")
    public List<String> listFiles() {
        return listFiles(".");
    }

    @AgentDoc(value = "Lists files in a specific directory using RipGrep, respecting .gitignore.", examples = "grepService.listFiles('src/main')")
    public List<String> listFiles(@AgentDoc("The directory path relative to the project root") String relativePath) {
        if (!projectService.hasActiveProject()) {
            return new ArrayList<>();
        }
        Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
        String relSearchDir = projectService.resolveRelativePath(relativePath);

        List<String> command = new ArrayList<>(List.of("rg", "--files", "--color=never"));
        if (!".".equals(relSearchDir)) {
            command.add(relSearchDir);
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
                String rootPrefix = normalizeRootPrefix(root);
                for (String line : output.lines().toList()) {
                    if (!line.isBlank()) {
                        String normalized = line.replace(BACKSLASH, '/');
                        if (!rootPrefix.isEmpty()) {
                            normalized = StringUtils.removeStart(normalized, rootPrefix);
                        }
                        files.add(StringUtils.removeStart(normalized, "./"));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list files using RipGrep: {}", e.getMessage());
        }
        return files;
    }

    private static String normalizeRootPrefix(Path root) {
        if (root == null)
            return "";
        String prefix = root.toAbsolutePath().normalize().toString().replace(BACKSLASH, '/');
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String stripRootPrefix(String rawOutput, Path root) {
        if (rawOutput == null || rawOutput.isEmpty() || root == null) {
            return rawOutput == null ? "" : rawOutput;
        }
        String rawRootPrefix = root.toAbsolutePath().normalize().toString();
        String rawPrefixWithSep = rawRootPrefix.endsWith(File.separator)
                ? rawRootPrefix
                : rawRootPrefix + File.separator;
        String slashPrefix = normalizeRootPrefix(root);
        List<String> lines = rawOutput.lines().toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0)
                sb.append(LF);
            String line = lines.get(i);
            if (line.startsWith(rawPrefixWithSep)) {
                line = StringUtils.removeStart(line, rawPrefixWithSep);
            } else if (!slashPrefix.isEmpty()) {
                line = StringUtils.removeStart(line, slashPrefix);
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
