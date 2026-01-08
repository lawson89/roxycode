package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for interacting with Git version control.
 * Provides methods for checking status, branches, diffs, and logs.
 */
@ScriptService("gitService")
@Singleton
public class GitService {
    private final Sandbox sandbox;

    @Inject
    public GitService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Retrieves the name of the currently checked-out Git branch.
     *
     * @return The current branch name.
     */
    @LLMDoc("Returns the current Git branch name")
    public String getCurrentBranch() {
        return GitRunner.runGitCommand(sandbox.getRoot(), "branch", "--show-current").trim();
    }

    /**
     * Retrieves the current Git status in porcelain format.
     *
     * @return The Git status output.
     */
    @LLMDoc("Returns the Git status in porcelain format")
    public String getStatus() {
        return GitRunner.runGitCommand(sandbox.getRoot(), "status", "--porcelain");
    }

    /**
     * Retrieves the Git diff.
     *
     * @param cached Whether to show staged (cached) changes.
     * @param path   An optional path to limit the diff to.
     * @return The Git diff output.
     */
    @LLMDoc("Returns the Git diff, optionally cached (staged) and for a specific path")
    public String diff(boolean cached, String path) {
        List<String> args = new ArrayList<>();
        args.add("diff");
        if (cached) {
            args.add("--cached");
        }
        if (path != null && !path.isEmpty()) {
            args.add(path);
        }
        return GitRunner.runGitCommand(sandbox.getRoot(), args.toArray(new String[0]));
    }

    /**
     * Retrieves the Git commit log.
     *
     * @param path  An optional path to limit the log to.
     * @param limit The maximum number of log entries to retrieve.
     * @return The Git log output.
     */
    @LLMDoc("Returns the Git log for a path with a specified limit on the number of entries")
    public String log(String path, int limit) {
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("-n");
        args.add(String.valueOf(limit));
        if (path != null && !path.isEmpty()) {
            args.add(path);
        }
        return GitRunner.runGitCommand(sandbox.getRoot(), args.toArray(new String[0])).trim();
    }
}
