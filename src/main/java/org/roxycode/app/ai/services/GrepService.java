package org.roxycode.app.ai.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.ProjectService.ResolvedSearchTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AgentService(value = "grepService", phases = {"*"})
@AgentDoc("Provides powerful text searching capabilities using RipGrep across the project and mounted @<alias> reference folders, respecting .gitignore and sandbox boundaries.")
public class GrepService {
    private static final Logger log = LoggerFactory.getLogger(GrepService.class);
    static final int MAX_OUTPUT_LINES = 200;
    static final int MAX_CONTEXT_LINES = 20;
    static final int MAX_READ_MATCHING_FILES = 10;
    static final int EXIT_CODE_NO_MATCHES = 1;
    private static final String DOC_REGEX_PATTERN = "The regex pattern to search for";
    private static final String DOC_RELATIVE_PATH = "The directory or file path relative to the project root (or '@<alias>' / '@<alias>/subpath' for a reference folder)";
    private static final String DOC_FILE_PATTERN = "Optional glob pattern for filtering files (e.g., '*.java')";
    private static final String DOC_CONTEXT_LINES = "Number of context lines before and after each match (0-20)";
    private static final String DOC_LITERAL_TEXT = "The literal text to search for";
    private static final String DOC_IGNORE_CASE_PATTERN = "The regex pattern to search for case-insensitively";
    private static final String CURRENT_DIR = ".";
    private static final String RG_CMD = "rg";
    private static final String COLOR_NEVER_ARG = "--color=never";
    private static final String GLOB_FLAG = "-g";
    private static final String END_OF_OPTIONS = "--";
    private static final char LF = '\n';
    private final ProjectService projectService;
    private final FileReadService fileReadService;

    public GrepService(ProjectService projectService, FileReadService fileReadService) {
        this.projectService = projectService;
        this.fileReadService = fileReadService;
    }

    private boolean canExecuteSearch(String relativePath) {
        return projectService.hasActiveProject() || ProjectService.isReferencePath(relativePath);
    }

    private ResolvedSearchTarget resolveTarget(String relativePath) {
        return projectService.resolveSearchTarget(relativePath);
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files using RipGrep.", examples = "grepService.grep('TODO')")
    public EditorResult grep(@AgentDoc(DOC_REGEX_PATTERN) String pattern) {
        return grep(pattern, CURRENT_DIR, null);
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files using RipGrep.", examples = "grepService.grep('import', 'src', '*.java')")
    public EditorResult grep(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(pattern, relativePath, filePattern, 0, false, false);
    }

    @AgentDoc(value = "Searches for a regex pattern in the project files with surrounding context lines.", examples = "grepService.grepWithContext('@AgentDoc', 3)")
    public EditorResult grepWithContext(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_CONTEXT_LINES) int contextLines) {
        return grepWithContext(pattern, CURRENT_DIR, null, contextLines);
    }

    @AgentDoc(value = "Searches for a regex pattern in the specified path and file glob with surrounding context lines.", examples = "grepService.grepWithContext('public void', 'src', '*.java', 3)")
    public EditorResult grepWithContext(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern,
            @AgentDoc(DOC_CONTEXT_LINES) int contextLines) {
        return executeRipGrep(pattern, relativePath, filePattern, contextLines, false, false);
    }

    @AgentDoc(value = "Searches for a literal fixed string (no regex escaping needed) in the project files.", examples = "grepService.grepLiteral('std::vector<int>')")
    public EditorResult grepLiteral(@AgentDoc(DOC_LITERAL_TEXT) String text) {
        return grepLiteral(text, CURRENT_DIR, null);
    }

    @AgentDoc(value = "Searches for a literal fixed string (no regex escaping needed) in the specified path and file glob.", examples = "grepService.grepLiteral('List<String>', 'src', '*.java')")
    public EditorResult grepLiteral(@AgentDoc(DOC_LITERAL_TEXT) String text,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(text, relativePath, filePattern, 0, true, false);
    }

    @AgentDoc(value = "Searches for a regex pattern case-insensitively in the project files.", examples = "grepService.grepIgnoreCase('fixme')")
    public EditorResult grepIgnoreCase(@AgentDoc(DOC_IGNORE_CASE_PATTERN) String pattern) {
        return grepIgnoreCase(pattern, CURRENT_DIR, null);
    }

    @AgentDoc(value = "Searches for a regex pattern case-insensitively in the specified path and file glob.", examples = "grepService.grepIgnoreCase('todo', 'src', '*.java')")
    public EditorResult grepIgnoreCase(@AgentDoc(DOC_IGNORE_CASE_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        return executeRipGrep(pattern, relativePath, filePattern, 0, false, true);
    }

    @AgentDoc(value = "Finds all files containing matches for the given regex pattern using RipGrep (-l), returning a deduplicated list of relative file paths.", examples = "grepService.findMatchingFiles('class AuthService')")
    public List<String> findMatchingFiles(@AgentDoc(DOC_REGEX_PATTERN) String pattern) {
        return findMatchingFiles(pattern, CURRENT_DIR, null);
    }

    @AgentDoc(value = "Finds all files in the specified path and file glob containing matches for the given regex pattern using RipGrep (-l), returning a deduplicated list of relative file paths (prefixed with '@<alias>/' when searching a reference folder).", examples = {
            "grepService.findMatchingFiles('@AgentService', 'src', '*.java')",
            "grepService.findMatchingFiles('class Invoice', '@billing-service', '*.java')"})
    public List<String> findMatchingFiles(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        if (!canExecuteSearch(relativePath) || pattern == null || pattern.isEmpty()) {
            return new ArrayList<>();
        }
        ResolvedSearchTarget target = resolveTarget(relativePath);

        List<String> command = new ArrayList<>(List.of(RG_CMD, "-l", COLOR_NEVER_ARG));
        if (filePattern != null && !filePattern.isBlank()) {
            command.addAll(List.of(GLOB_FLAG, filePattern));
        }
        command.addAll(List.of(END_OF_OPTIONS, pattern, target.relativeTarget()));

        Set<String> matches = new LinkedHashSet<>();
        for (String line : runRipGrepLines(command, target.workingDir(), true)) {
            matches.add(target.formatResultPath(line));
        }
        return new ArrayList<>(matches);
    }

    @AgentDoc(value = "Finds files matching the given regex pattern and immediately reads their contents into a Map of relativePath to content (capped at 10 files).", examples = "grepService.readMatchingFiles('class AuthService')")
    public Map<String, String> readMatchingFiles(@AgentDoc(DOC_REGEX_PATTERN) String pattern) {
        return readMatchingFiles(pattern, CURRENT_DIR, null);
    }

    @AgentDoc(value = "Finds files in the specified path and file glob matching the given regex pattern and immediately reads their contents into a Map of relativePath to content (capped at 10 files).", examples = "grepService.readMatchingFiles('implements ToolCallback', 'src', '*.java')")
    public Map<String, String> readMatchingFiles(@AgentDoc(DOC_REGEX_PATTERN) String pattern,
            @AgentDoc(DOC_RELATIVE_PATH) String relativePath, @AgentDoc(DOC_FILE_PATTERN) String filePattern) {
        List<String> matchingFiles = findMatchingFiles(pattern, relativePath, filePattern);
        if (matchingFiles.isEmpty() || fileReadService == null) {
            return new LinkedHashMap<>();
        }
        if (matchingFiles.size() > MAX_READ_MATCHING_FILES) {
            List<String> subset = matchingFiles.subList(0, MAX_READ_MATCHING_FILES);
            Map<String, String> results = new LinkedHashMap<>(fileReadService.readFiles(subset));
            int omitted = matchingFiles.size() - MAX_READ_MATCHING_FILES;
            results.put(FileReadService.TRUNCATED_KEY, "Matched " + matchingFiles.size() + " files; showing first "
                    + MAX_READ_MATCHING_FILES + " files (" + omitted + " more matching file(s) omitted).");
            return results;
        }
        return fileReadService.readFiles(matchingFiles);
    }

    List<String> grepWordMatches(String wordOrPattern, String relativePath, boolean fixedWord) {
        if (!canExecuteSearch(relativePath) || wordOrPattern == null || wordOrPattern.isEmpty()) {
            return new ArrayList<>();
        }
        ResolvedSearchTarget target = resolveTarget(relativePath);

        List<String> command = new ArrayList<>(List.of(RG_CMD, "--vimgrep", "--no-heading", COLOR_NEVER_ARG));
        if (fixedWord) {
            command.add("-w");
        } else {
            command.add("-F");
        }
        command.addAll(List.of(END_OF_OPTIONS, wordOrPattern, target.relativeTarget()));
        List<String> rawLines = runRipGrepLines(command, target.workingDir(), false);
        if (!target.isReferenceMount()) {
            return rawLines;
        }
        List<String> prefixed = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            prefixed.add(target.formatMatchLine(line));
        }
        return prefixed;
    }

    private EditorResult executeRipGrep(String pattern, String relativePath, String filePattern, int contextLines,
            boolean fixedStrings, boolean ignoreCase) {
        if (!canExecuteSearch(relativePath)) {
            return new EditorResult(false, "", "No active project");
        }
        if (pattern == null || pattern.isEmpty()) {
            return new EditorResult(false, "", "Search pattern must not be empty");
        }

        ResolvedSearchTarget target = resolveTarget(relativePath);
        int clampedContext = Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));

        List<String> command = new ArrayList<>();
        command.add(RG_CMD);
        if (clampedContext > 0) {
            command.addAll(List.of("-n", "-C", String.valueOf(clampedContext)));
        } else {
            command.add("--vimgrep");
        }
        command.addAll(List.of("--no-heading", COLOR_NEVER_ARG));

        if (fixedStrings) {
            command.add("-F");
        }
        if (ignoreCase) {
            command.add("-i");
        }
        if (filePattern != null && !filePattern.isBlank()) {
            command.addAll(List.of(GLOB_FLAG, filePattern));
        }
        command.addAll(List.of(END_OF_OPTIONS, pattern, target.relativeTarget()));

        return runRipGrepSearch(command, target.workingDir(),
                rawOutput -> String.join(String.valueOf(LF), rawOutput.lines().map(target::formatMatchLine).toList()));
    }

    static List<String> runRipGrepLines(List<String> command, Path workingDir, boolean redirectErrorStream) {
        ProcessResult result = ProcessRunner.run(command, workingDir, redirectErrorStream);
        if (result.timedOut()) {
            log.warn("RipGrep command timed out in {}: {}", workingDir, command);
            return List.of();
        }
        if (result.isSuccess()) {
            return result.outputLines().stream().filter(line -> !line.isBlank()).toList();
        }
        return List.of();
    }

    static EditorResult runRipGrepSearch(List<String> command, Path workingDir,
            UnaryOperator<String> outputNormalizer) {
        ProcessResult result = ProcessRunner.run(command, workingDir, true);
        if (result.timedOut()) {
            log.warn("RipGrep timed out: {}", command);
            return new EditorResult(false, "", "RipGrep timed out");
        }
        int exitCode = result.exitCode();
        if (exitCode == EXIT_CODE_NO_MATCHES) {
            return new EditorResult(true, "", null);
        }
        String trimmedOutput = StringUtils.trimToEmpty(result.output());
        if (exitCode != 0) {
            String errorDetail = StringUtils.defaultIfEmpty(trimmedOutput,
                    StringUtils.trimToEmpty(result.errorOutput()));
            log.warn("RipGrep failed with exit code {}: {}", exitCode, errorDetail);
            return new EditorResult(false, "", "RipGrep error (" + exitCode + "): " + errorDetail);
        }
        String normalized = outputNormalizer.apply(trimmedOutput);
        return new EditorResult(true, capOutputLines(normalized, MAX_OUTPUT_LINES), null);
    }

    static String capOutputLines(String rawOutput, int maxLines) {
        if (rawOutput == null) {
            return "";
        }
        if (rawOutput.isEmpty() || maxLines <= 0) {
            return rawOutput;
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
        return listFiles(CURRENT_DIR);
    }

    @AgentDoc(value = "Lists files in a specific directory or mounted @<alias> reference folder using RipGrep, respecting .gitignore.", examples = {
            "grepService.listFiles('src/main')", "grepService.listFiles('@billing-service')"})
    public List<String> listFiles(
            @AgentDoc("The directory path relative to the project root (or '@<alias>' / '@<alias>/subpath')") String relativePath) {
        if (!canExecuteSearch(relativePath)) {
            return new ArrayList<>();
        }
        ResolvedSearchTarget target = resolveTarget(relativePath);

        List<String> command = new ArrayList<>(List.of(RG_CMD, "--files", COLOR_NEVER_ARG));
        if (!CURRENT_DIR.equals(target.relativeTarget())) {
            command.add(target.relativeTarget());
        }

        List<String> files = new ArrayList<>();
        for (String line : runRipGrepLines(command, target.workingDir(), false)) {
            files.add(target.formatResultPath(line));
        }
        return files;
    }
}
