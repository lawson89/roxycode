package org.roxycode.app.ai.services;

/**
 * Result of a file operation or search.
 */
public record EditorResult(
    boolean success,
    String content,
    String errorHint
) {}
