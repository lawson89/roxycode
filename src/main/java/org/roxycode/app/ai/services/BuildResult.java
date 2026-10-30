package org.roxycode.app.ai.services;

import java.util.List;

/** Result of a build tool command. */
public record BuildResult(boolean success, int exitCode, String output, List<String> errors, List<String> failedTests) {

    public BuildResult {
        if (errors == null) {
            errors = List.of();
        } else {
            errors = List.copyOf(errors);
        }
        if (failedTests == null) {
            failedTests = List.of();
        } else {
            failedTests = List.copyOf(failedTests);
        }
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
}
