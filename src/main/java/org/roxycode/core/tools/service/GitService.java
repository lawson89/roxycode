package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.util.ArrayList;
import java.util.List;

@ScriptService("gitService")
@Singleton
public class GitService {
    private final Sandbox sandbox;

    @Inject
    public GitService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @LLMDoc("Returns the current Git branch name")
    public String getCurrentBranch() {
        return GitRunner.runGitCommand(sandbox.getRoot(), "branch", "--show-current");
    }

    @LLMDoc("Returns the Git status in porcelain format")
    public String getStatus() {
        return GitRunner.runGitCommand(sandbox.getRoot(), "status", "--porcelain");
    }

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

    @LLMDoc("Returns the Git log for a path with a specified limit on the number of entries")
    public String log(String path, int limit) {
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("-n");
        args.add(String.valueOf(limit));
        if (path != null && !path.isEmpty()) {
            args.add(path);
        }
        return GitRunner.runGitCommand(sandbox.getRoot(), args.toArray(new String[0]));
    }
}
