package org.roxycode.app.ai.services;

import java.util.List;

/** Result of a build tool command. */
public record BuildResult(
        boolean success,
        int exitCode,
        String output,
        List<String> errors,
        List<String> failedTests,
        String log) {

    public BuildResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        failedTests = failedTests == null ? List.of() : List.copyOf(failedTests);
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
