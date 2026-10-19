package org.roxycode.app.ai.services;

import java.util.List;

/** Result of a build tool command. */
public final class BuildResult {
    private final boolean success;
    private final int exitCode;
    private final String output;
    private final List<String> errors;
    private final List<String> failedTests;
    private final String log;

    public BuildResult(
            boolean success,
            int exitCode,
            String output,
            List<String> errors,
            List<String> failedTests,
            String log) {
        this.success = success;
        this.exitCode = exitCode;
        this.output = output;
        this.errors = errors;
        this.failedTests = failedTests;
        this.log = log;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getFailedTests() {
        return failedTests;
    }

    public String getLog() {
        return log;
    }
}
