package org.roxycode.app.ai.services;

/** Result of a file operation or search. */
public record EditorResult(boolean success, String content, String errorHint) {

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
