package org.roxycode.app.ai.services;

import java.util.List;
import java.util.Objects;

/** Result of a build tool command. */
public record BuildResult(boolean success, int exitCode, String output, List<String> errors, List<String> failedTests,
        boolean timedOut) {

    public BuildResult {
        errors = List.copyOf(Objects.requireNonNullElse(errors, List.of()));
        failedTests = List.copyOf(Objects.requireNonNullElse(failedTests, List.of()));
    }

    public BuildResult(boolean success, int exitCode, String output, List<String> errors, List<String> failedTests) {
        this(success, exitCode, output, errors, failedTests, false);
    }
}