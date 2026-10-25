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

/** Service for read-only Git operations. */
@AgentService(
        value = "gitService",
        phases = {"*"})
@AgentDoc("Provides read-only access to Git repository information for the current project.")
public class GitService {
    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String NOT_A_GIT_REPOSITORY = "Not a git repository";
    private static final String GIT_DIFF = "diff";
    private static final int MIN_PORCELAIN_LINE_LENGTH = 4;
    private static final char NL = (char) 10;
    private static final char DQUOTE = (char) 34;
    private static final char BACKSLASH = (char) 92;

    private final ProjectService projectService;

    public GitService(ProjectService projectService) {
        this.projectService = projectService;
    }

    @AgentDoc(
            value = "Returns the name of the current Git branch.",
            examples = "gitService.getCurrentBranch()")
    public String getCurrentBranch() {
        return executeGitCommand("rev-parse", "--abbrev-ref", "HEAD");
    }

    @AgentDoc(value = "Returns the git status (porcelain).", examples = "gitService.getStatus()")
    public String getStatus() {
        return executeGitCommand("status", "--porcelain");
    }

    @AgentDoc(
            value =
                    "Returns the git diff including staged, unstaged, and untracked changes in the working tree.",
            examples = "gitService.getDiff()")
    public String getDiff() {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        String headDiff = getDiffHead();
        if (isGitError(headDiff)) {
            return headDiff;
        }
        String untrackedDiff = buildUntrackedDiffs(null);
        return combineDiffs(headDiff, untrackedDiff);
    }

    @AgentDoc(
            value =
                    "Returns the git diff (including staged, unstaged, and untracked changes) for a specific file or directory.",
            examples = "gitService.getDiff('src/main/java/App.java')")
    public String getDiff(
            @AgentDoc("The file or directory path relative to the project root")
                    String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return getDiff();
        }
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        String normalizedPath = resolveRelativeSandboxPath(relativePath);
        String headDiff = getDiffHead(normalizedPath);
        if (isGitError(headDiff)) {
            return headDiff;
        }
        String untrackedDiff = buildUntrackedDiffs(normalizedPath);
        return combineDiffs(headDiff, untrackedDiff);
    }

    @AgentDoc(
            value =
                    "Returns the git diff of all tracked files (staged and unstaged) compared against HEAD.",
            examples = "gitService.getDiffHead()")
    public String getDiffHead() {
        return computeTrackedDiffAgainstHead(null);
    }

    @AgentDoc(
            value =
                    "Returns the git diff of a tracked file or directory (staged and unstaged) compared against HEAD.",
            examples = "gitService.getDiffHead('src/main/java/App.java')")
    public String getDiffHead(
            @AgentDoc("The file or directory path relative to the project root")
                    String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return getDiffHead();
        }
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        String normalizedPath = resolveRelativeSandboxPath(relativePath);
        return computeTrackedDiffAgainstHead(normalizedPath);
    }

    @AgentDoc(
            value =
                    "Returns a list of all modified, staged, deleted, and untracked file paths relative to the project root.",
            examples = "gitService.getChangedFiles()")
    public List<String> getChangedFiles() {
        if (!projectService.hasActiveProject()) {
            return List.of();
        }
        String statusOutput = executeGitCommand("status", "--porcelain", "-uall");
        if (statusOutput.isEmpty() || isGitError(statusOutput)) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        for (String line : statusOutput.lines().toList()) {
            if (line.length() < MIN_PORCELAIN_LINE_LENGTH) {
                continue;
            }
            String rawPath = line.substring(3).trim();
            int arrowIdx = rawPath.indexOf(" -> ");
            if (arrowIdx >= 0) {
                rawPath = rawPath.substring(arrowIdx + 4).trim();
            }
            if (rawPath.length() >= 2
                    && rawPath.charAt(0) == DQUOTE
                    && rawPath.charAt(rawPath.length() - 1) == DQUOTE) {
                rawPath = rawPath.substring(1, rawPath.length() - 1);
            }
            if (!rawPath.isEmpty()) {
                files.add(rawPath.replace(BACKSLASH, '/'));
            }
        }
        return List.copyOf(files);
    }

    @AgentDoc(
            value = "Returns git blame annotations for a 1-based inclusive line range of a file.",
            examples = "gitService.getBlame('src/main/java/App.java', 1, 30)")
    public String getBlame(
            @AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The 1-based start line number (inclusive)") int startLine,
            @AgentDoc("The 1-based end line number (inclusive)") int endLine) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Error: File path must not be blank");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException(
                    "Error: Invalid line range: "
                            + startLine
                            + " to "
                            + endLine
                            + " (startLine must be >= 1 and endLine >= startLine)");
        }
        String normalizedPath = resolveRelativeSandboxPath(relativePath);
        return executeGitCommand("blame", "-L", startLine + "," + endLine, "--", normalizedPath);
    }

    @AgentDoc(value = "Returns the git log (last 10 commits).", examples = "gitService.getLog()")
    public String getLog() {
        return getLog(10);
    }

    @AgentDoc(value = "Returns the git log.", examples = "gitService.getLog(5)")
    public String getLog(@AgentDoc("Maximum number of commits to return") int limit) {
        return executeGitCommand("log", "-" + limit, "--oneline");
    }

    @AgentDoc(
            value = "Shows details for a specific commit.",
            examples = "gitService.showCommit('abcd123')")
    public String showCommit(@AgentDoc("The commit hash or reference") String hash) {
        return executeGitCommand("show", hash);
    }

    private String resolveRelativeSandboxPath(String relativePath) {
        Path root = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
        Path resolved = projectService.resolvePath(relativePath.trim());
        String rel = root.relativize(resolved).toString().replace(BACKSLASH, '/');
        return rel.isEmpty() ? "." : rel;
    }

    private String computeTrackedDiffAgainstHead(String normalizedPath) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }
        String headOut =
                normalizedPath == null
                        ? executeGitCommand(GIT_DIFF, "HEAD")
                        : executeGitCommand(GIT_DIFF, "HEAD", "--", normalizedPath);
        if (!isGitError(headOut)) {
            return headOut;
        }
        if (headOut.contains(NOT_A_GIT_REPOSITORY) || headOut.contains("Command timed out")) {
            return headOut;
        }
        // Fallback for unborn HEAD (initial commit not created yet)
        String staged =
                normalizedPath == null
                        ? executeGitCommand(GIT_DIFF, "--cached")
                        : executeGitCommand(GIT_DIFF, "--cached", "--", normalizedPath);
        if (isGitError(staged)) {
            return staged;
        }
        String unstaged =
                normalizedPath == null
                        ? executeGitCommand(GIT_DIFF)
                        : executeGitCommand(GIT_DIFF, "--", normalizedPath);
        if (isGitError(unstaged)) {
            return unstaged;
        }
        return combineDiffs(staged, unstaged);
    }

    private String buildUntrackedDiffs(String normalizedPath) {
        String untrackedOut =
                normalizedPath == null
                        ? executeGitCommand("ls-files", "--others", "--exclude-standard")
                        : executeGitCommand(
                                "ls-files", "--others", "--exclude-standard", "--", normalizedPath);
        if (untrackedOut.isEmpty() || isGitError(untrackedOut)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : untrackedOut.lines().toList()) {
            String relFile = line.trim().replace(BACKSLASH, '/');
            if (relFile.isEmpty()) {
                continue;
            }
            Path filePath;
            try {
                filePath = projectService.resolvePath(relFile);
            } catch (SecurityException e) {
                continue;
            }
            if (!Files.isRegularFile(filePath)) {
                continue;
            }
            String formatted = formatUntrackedFileDiff(relFile, filePath);
            if (!formatted.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(NL);
                }
                sb.append(formatted);
            }
        }
        return sb.toString();
    }

    private String formatUntrackedFileDiff(String relFile, Path filePath) {
        try {
            if (FileReadService.isBinaryFile(filePath)) {
                return "diff --git a/"
                        + relFile
                        + " b/"
                        + relFile
                        + NL
                        + "new file mode 100644"
                        + NL
                        + "Binary files /dev/null and b/"
                        + relFile
                        + " differ";
            }
            String content = Files.readString(filePath);
            StringBuilder sb = new StringBuilder();
            sb.append("diff --git a/").append(relFile).append(" b/").append(relFile).append(NL);
            sb.append("new file mode 100644").append(NL);
            sb.append("--- /dev/null").append(NL);
            sb.append("+++ b/").append(relFile);
            if (content.isEmpty()) {
                return sb.toString();
            }
            List<String> lines = content.lines().toList();
            int lineCount = lines.size();
            sb.append(NL).append("@@ -0,0 +1,").append(lineCount).append(" @@");
            for (int i = 0; i < lineCount; i++) {
                sb.append(NL).append('+').append(lines.get(i));
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("Failed to read untracked file {} for diff: {}", relFile, e.getMessage());
            return "";
        }
    }

    private static boolean isGitError(String output) {
        return output != null
                && (NO_ACTIVE_PROJECT.equals(output)
                        || NOT_A_GIT_REPOSITORY.equals(output)
                        || output.startsWith("Error"));
    }

    private static String combineDiffs(String first, String second) {
        String a = first == null ? "" : first.trim();
        String b = second == null ? "" : second.trim();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + NL + b;
    }

    private String executeGitCommand(String... args) {
        if (!projectService.hasActiveProject()) {
            return NO_ACTIVE_PROJECT;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));

            ProcessResult result =
                    ProcessRunner.run(
                            command,
                            projectService.getCurrentProjectRoot(),
                            Map.of("GIT_TERMINAL_PROMPT", "0"));

            if (result.timedOut()) {
                log.warn("Git command {} timed out", String.join(" ", args));
                return "Error executing git command: Command timed out";
            }

            int exitCode = result.exitCode();
            String out = result.output();
            if (exitCode != 0) {
                if (out.contains("not a git repository")) {
                    return "Not a git repository";
                }
                log.warn(
                        "Git command {} failed with exit code {}: {}",
                        String.join(" ", args),
                        exitCode,
                        out.trim());
                return "Error (" + exitCode + "): " + out.trim();
            }

            return out.trim();
        } catch (IOException e) {
            log.error(
                    "Exception executing git command {}: {}",
                    String.join(" ", args),
                    e.getMessage(),
                    e);
            return "Error executing git command: " + e.getMessage();
        }
    }
}
