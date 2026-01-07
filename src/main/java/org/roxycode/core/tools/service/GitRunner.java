package org.roxycode.core.tools.service;

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

public class GitRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GitRunner.class);

    private static final int TIMEOUT_SECONDS = 30;

    public static String runGitCommand(Path projectRoot, String... args) {
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
