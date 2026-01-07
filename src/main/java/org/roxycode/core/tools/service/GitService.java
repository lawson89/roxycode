package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ScriptService("gitService")
@Singleton
public class GitService {
    private static final Logger LOG = LoggerFactory.getLogger(GitService.class);
    private static final int TIMEOUT_SECONDS = 10; // Increased timeout for diffs

    private final Sandbox sandbox;

    @Inject
    public GitService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    private Path resolveRoot(Object projectRoot) {
        if (projectRoot instanceof Path path) {
            return path;
        }
        if (projectRoot instanceof String pathStr) {
            return sandbox.resolve(pathStr);
        }
        if (projectRoot != null) {
            // GraalJS might pass it as something else that toString() can handle
            return sandbox.resolve(projectRoot.toString());
        }
        return sandbox.getRoot();
    }

    @LLMDoc("Returns the current Git branch name")
    public String getCurrentBranch(Object projectRoot) {
        return runGitCommand(resolveRoot(projectRoot), "branch", "--show-current");
    }

    @LLMDoc("Returns the Git status in porcelain format")
    public String getStatus(Object projectRoot) {
        return runGitCommand(resolveRoot(projectRoot), "status", "--porcelain");
    }

    @LLMDoc("Returns the Git diff, optionally cached (staged) and for a specific path")
    public String diff(Object projectRoot, boolean cached, String path) {
        List<String> args = new ArrayList<>();
        args.add("diff");
        if (cached) {
            args.add("--cached");
        }
        if (path != null && !path.isEmpty()) {
            args.add(path);
        }
        return runGitCommand(resolveRoot(projectRoot), args.toArray(new String[0]));
    }

    @LLMDoc("Returns the Git log for a path with a specified limit on the number of entries")
    public String log(Object projectRoot, String path, int limit) {
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("-n");
        args.add(String.valueOf(limit));
        if (path != null && !path.isEmpty()) {
            args.add(path);
        }
        return runGitCommand(resolveRoot(projectRoot), args.toArray(new String[0]));
    }

    private String runGitCommand(Path projectRoot, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));

            return new ProcessExecutor()
                    .command(command)
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
