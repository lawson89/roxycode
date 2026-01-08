package org.roxycode.core.tools.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility class for executing Git commands.
 */
public class GitRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GitRunner.class);

    private static final int TIMEOUT_SECONDS = 30;

    /**
     * Executes a Git command with the specified arguments in the given project root directory.
     *
     * @param projectRoot The directory where the Git command should be executed.
     * @param args        The arguments for the Git command.
     * @return The output of the Git command as a string, or an error message if the command fails.
     */
    public static String runGitCommand(Path projectRoot, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));

            String output = new ProcessExecutor()
                    .command(command)
                    .directory(projectRoot.toFile())
                    .readOutput(true)
                    .timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .execute()
                    .outputUTF8();
            return StringUtils.stripEnd(output, null);
        } catch (IOException | InterruptedException | TimeoutException e) {
            LOG.warn("Git command failed: {} in {}", args, projectRoot, e);
            return "Error: " + e.getMessage();
        }
    }
}
