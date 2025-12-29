package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Singleton
public class GitService {
    private static final Logger LOG = LoggerFactory.getLogger(GitService.class);
    private static final int TIMEOUT_SECONDS = 2;

    /**
     * Returns the current branch name.
     * Exec: git branch --show-current
     */
    public String getCurrentBranch(Path projectRoot) {
        return runGitCommand(projectRoot, "branch", "--show-current");
    }

    /**
     * Returns the status porcelain.
     * Exec: git status --porcelain
     */
    public String getStatus(Path projectRoot) {
        return runGitCommand(projectRoot, "status", "--porcelain");
    }

    private String runGitCommand(Path projectRoot, String... args) {
        try {
            // Construct command: git [args...]
            return new ProcessExecutor()
                    .command("git", args[0], (args.length > 1 ? args[1] : ""))
                    .directory(projectRoot.toFile())
                    .readOutput(true)
                    .timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .execute()
                    .outputUTF8()
                    .trim();
        } catch (IOException | InterruptedException | TimeoutException e) {
            LOG.warn("Git command failed: {} in {}", args, projectRoot, e);
            return "Error: " + e.getMessage();
        }
    }
}