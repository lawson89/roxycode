package org.roxycode.app.ai.services;

/**
 * Result of a file operation or search.
 */
public final class EditorResult {
    private final boolean success;
    private final String content;
    private final String errorHint;

    public EditorResult(boolean success, String content, String errorHint) {
        this.success = success;
        this.content = content;
        this.errorHint = errorHint;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    } 

    public String getErrorHint() {
        return errorHint;
    }
}
