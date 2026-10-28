package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AgentService(value = "gitService", phases = {"*"})
@AgentDoc("Provides read-only access to Git repository information for the current project.")
public class GitService {
    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String NOT_A_GIT_REPOSITORY = "Not a git repository";
    private static final String GIT_DIFF = "diff";
    private static final int MIN_PORCELAIN_LINE_LENGTH = 4;
    private static final char NL = 10;
    private static final char BACKSLASH = 92;
    private final ProjectService projectService;

    public GitService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc(value = "Returns the name of the current Git branch.", examples = "gitService.getCurrentBranch()")
    public String getCurrentBranch() {
        return executeGitCommand("rev-parse", "--abbrev-ref", "HEAD");
    }

    @AgentDoc(value = "Returns the git status (porcelain).", examples = "gitService.getStatus()")
    public String getStatus() {
        return executeGitCommand(false, "-c", "core.quotePath=false", "status", "--porcelain");
    }

    @AgentDoc(value = "Returns the git diff including staged, unstaged, and untracked changes in the working tree.", examples = "gitService.getDiff()")
    public String getDiff() {
        return computeDiff(null);
    }

    @AgentDoc(value = "Returns the git diff (including staged, unstaged, and untracked changes) for a specific file or directory.", examples = "gitService.getDiff('src/main/java/App.java')")
    public String getDiff(@AgentDoc("The file or directory path relative to the project root") String relativePath) {
        return (relativePath == null || relativePath.isBlank()) ? getDiff() : computeDiff(relativePath);
    }

    private String computeDiff(String relativePath) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        String normalizedPath = relativePath != null ? projectService.resolveRelativePath(relativePath) : null;
        String headDiff = computeTrackedDiffAgainstHead(normalizedPath);
        if (isGitError(headDiff)) {
            return headDiff;
        }
        return combineDiffs(headDiff, buildUntrackedDiffs(normalizedPath));
    }

    @AgentDoc(value = "Returns the git diff of all tracked files (staged and unstaged) compared against HEAD.", examples = "gitService.getDiffHead()")
    public String getDiffHead() {
        return computeTrackedDiffAgainstHead(null);
    }

    @AgentDoc(value = "Returns the git diff of a tracked file or directory (staged and unstaged) compared against HEAD.", examples = "gitService.getDiffHead('src/main/java/App.java')")
    public String getDiffHead(
            @AgentDoc("The file or directory path relative to the project root") String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return getDiffHead();
        }
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        return computeTrackedDiffAgainstHead(projectService.resolveRelativePath(relativePath));
    }

    @AgentDoc(value = "Returns a list of all modified, staged, deleted, and untracked file paths relative to the project root.", examples = "gitService.getChangedFiles()")
    public List<String> getChangedFiles() {
        if (!projectService.hasActiveProject()) {
            return List.of();
        }
        String statusOutput = executeGitCommand(false, "status", "--porcelain", "-z", "-uall");
        if (statusOutput.isEmpty() || isGitError(statusOutput)) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        String[] entries = statusOutput.split(String.valueOf((char) 0));
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i];
            if (entry.length() < MIN_PORCELAIN_LINE_LENGTH) {
                continue;
            }
            char x = entry.charAt(0);
            char y = entry.charAt(1);
            String rawPath = entry.substring(3);
            if ((x == 'R' || x == 'C' || y == 'R' || y == 'C') && i + 1 < entries.length) {
                i++;
            }
            if (!rawPath.isEmpty()) {
                files.add(rawPath.replace(BACKSLASH, '/'));
            }
        }
        return List.copyOf(files);
    }

    @AgentDoc(value = "Returns git blame annotations for a 1-based inclusive line range of a file.", examples = "gitService.getBlame('src/main/java/App.java', 1, 30)")
    public String getBlame(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The 1-based start line number (inclusive)") int startLine,
            @AgentDoc("The 1-based end line number (inclusive)") int endLine) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Error: File path must not be blank");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Error: Invalid line range: " + startLine + " to " + endLine
                    + " (startLine must be >= 1 and endLine >= startLine)");
        }
        return executeGitCommand("blame", "-L", startLine + "," + endLine, "--",
                projectService.resolveRelativePath(relativePath));
    }

    @AgentDoc(value = "Returns the git log (last 10 commits).", examples = "gitService.getLog()")
    public String getLog() {
        return getLog(10);
    }

    @AgentDoc(value = "Returns the git log.", examples = "gitService.getLog(5)")
    public String getLog(@AgentDoc("Maximum number of commits to return") int limit) {
        return executeGitCommand("log", "-" + limit, "--oneline");
    }

    @AgentDoc(value = "Shows details for a specific commit.", examples = "gitService.showCommit('abcd123')")
    public String showCommit(@AgentDoc("The commit hash or reference") String hash) {
        return executeGitCommand("show", hash);
    }

    private String executeGitCommandWithPath(String normalizedPath, String... baseArgs) {
        if (normalizedPath == null) {
            return executeGitCommand(baseArgs);
        }
        String[] fullArgs = Arrays.copyOf(baseArgs, baseArgs.length + 2);
        fullArgs[baseArgs.length] = "--";
        fullArgs[baseArgs.length + 1] = normalizedPath;
        return executeGitCommand(fullArgs);
    }

    private String computeTrackedDiffAgainstHead(String normalizedPath) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        String headOut = executeGitCommandWithPath(normalizedPath, GIT_DIFF, "HEAD");
        if (!isGitError(headOut) || headOut.contains(NOT_A_GIT_REPOSITORY) || headOut.contains("Command timed out")) {
            return headOut;
        }
        String staged = executeGitCommandWithPath(normalizedPath, GIT_DIFF, "--cached");
        if (isGitError(staged))
            return staged;
        String unstaged = executeGitCommandWithPath(normalizedPath, GIT_DIFF);
        return isGitError(unstaged) ? unstaged : combineDiffs(staged, unstaged);
    }

    private String buildUntrackedDiffs(String normalizedPath) {
        String untrackedOut = executeGitCommandWithPath(normalizedPath, "ls-files", "--others", "--exclude-standard");
        if (untrackedOut.isEmpty() || isGitError(untrackedOut)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : untrackedOut.lines().toList()) {
            String relFile = line.trim().replace(BACKSLASH, '/');
            if (relFile.isEmpty())
                continue;
            try {
                Path filePath = projectService.resolvePath(relFile);
                if (Files.isRegularFile(filePath)) {
                    String formatted = formatUntrackedFileDiff(relFile, filePath);
                    if (!formatted.isEmpty()) {
                        if (!sb.isEmpty())
                            sb.append(NL);
                        sb.append(formatted);
                    }
                }
            } catch (SecurityException ignored) {
                // Skip outside sandbox
            }
        }
        return sb.toString();
    }

    private String formatUntrackedFileDiff(String relFile, Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return "";
        }
        try {
            List<String> command = List.of("git", GIT_DIFF, "--no-index", "--", "/dev/null", relFile);
            ProcessResult result = ProcessRunner.run(command, projectService.getCurrentProjectRoot(),
                    Map.of("GIT_TERMINAL_PROMPT", "0"));
            if (result == null || result.timedOut() || (result.exitCode() != 0 && result.exitCode() != 1)) {
                return "";
            }
            return normalizeUntrackedNoIndexDiff(relFile, result.output());
        } catch (IOException e) {
            log.warn("Failed to run git diff --no-index for untracked file {}: {}", relFile, e.getMessage());
            return "";
        }
    }

    static String normalizeUntrackedNoIndexDiff(String relFile, String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return "";
        }
        List<String> lines = rawDiff.trim().lines().toList();
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(NL);
            }
            String line = lines.get(i);
            if (i == 0 && line.startsWith("diff --git ")) {
                sb.append("diff --git a/").append(relFile).append(" b/").append(relFile);
            } else if (line.startsWith("+++ ") && !line.equals("+++ b/" + relFile)) {
                sb.append("+++ b/").append(relFile);
            } else if (line.startsWith("Binary files ") && line.endsWith(" differ")) {
                sb.append("Binary files /dev/null and b/").append(relFile).append(" differ");
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static boolean isGitError(String output) {
        return output != null && (NO_ACTIVE_PROJECT.equals(output) || NOT_A_GIT_REPOSITORY.equals(output)
                || output.startsWith("Error"));
    }

    private static String combineDiffs(String first, String second) {
        String a = first == null ? "" : first.trim();
        String b = second == null ? "" : second.trim();
        if (a.isEmpty())
            return b;
        return b.isEmpty() ? a : a + NL + b;
    }

    private String executeGitCommand(String... args) {
        return executeGitCommand(true, args);
    }

    private String executeGitCommand(boolean trimLeadingWhitespace, String... args) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));

            ProcessResult result = ProcessRunner.run(command, projectService.getCurrentProjectRoot(),
                    Map.of("GIT_TERMINAL_PROMPT", "0"));
            if (result.timedOut()) {
                log.warn("Git command {} timed out", String.join(" ", args));
                return "Error executing git command: Command timed out";
            }
            int exitCode = result.exitCode();
            String out = result.output();
            if (exitCode != 0) {
                if (out.contains("not a git repository")) {
                    return NOT_A_GIT_REPOSITORY;
                }
                log.warn("Git command {} failed with exit code {}: {}", String.join(" ", args), exitCode, out.trim());
                return "Error (" + exitCode + "): " + out.trim();
            }
            return trimLeadingWhitespace ? out.trim() : out.stripTrailing();
        } catch (IOException e) {
            log.warn("Failed to execute git command {}: {}", String.join(" ", args), e.getMessage());
            return "Error executing git command: " + e.getMessage();
        }
    }
}
