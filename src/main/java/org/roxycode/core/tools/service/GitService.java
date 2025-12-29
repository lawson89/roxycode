package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
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

@Singleton
public class GitService {
    private static final Logger LOG = LoggerFactory.getLogger(GitService.class);
    private static final int TIMEOUT_SECONDS = 10; // Increased timeout for diffs

    public String getCurrentBranch(Path projectRoot) {
        return runGitCommand(projectRoot, "branch", "--show-current");
    }

    public String getStatus(Path projectRoot) {
        return runGitCommand(projectRoot, "status", "--porcelain");
    }

    public String diff(Path projectRoot, boolean cached, String path) {
        List<String> args = new ArrayList<>();
        args.add("diff");
        if (cached) {
            args.add("--cached");
        }
        if (path != null && !path.isEmpty()) {
            args.add(path);
        }
        return runGitCommand(projectRoot, args.toArray(new String[0]));
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
