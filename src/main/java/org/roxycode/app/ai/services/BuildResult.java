package org.roxycode.app.ai.services;

import java.util.List;

/**
 * Result of a build tool command.
 */
public record BuildResult(
    boolean success,
    int exitCode,
    String output,
    List<String> errors,
    List<String> failedTests,
    String log
) {}