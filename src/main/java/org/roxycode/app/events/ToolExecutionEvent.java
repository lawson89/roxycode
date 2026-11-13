package org.roxycode.app.events;

import org.jspecify.annotations.Nullable;

/**
 * Event published when a JEXL tool script execution completes.
 *
 * @param conversationId
 *            the conversation identifier
 * @param phase
 *            the workflow phase name during execution
 * @param script
 *            the executed JEXL script
 * @param result
 *            the execution result object
 * @param success
 *            true if execution succeeded, false otherwise
 * @param errorCategory
 *            the classified error category, or null on success
 * @param line
 *            1-based error line number, or null if unavailable
 * @param column
 *            1-based error column number, or null if unavailable
 * @param offendingLine
 *            the offending source line snippet, or null if unavailable
 * @param error
 *            the error message if execution failed
 * @param hint
 *            the actionable JEXL 3 hint if execution failed
 */
public record ToolExecutionEvent(String conversationId, @Nullable String phase, String script, @Nullable Object result,
        boolean success, @Nullable String errorCategory, @Nullable Integer line, @Nullable Integer column,
        @Nullable String offendingLine, @Nullable String error, @Nullable String hint) {

    /**
     * Backward-compatible convenience constructor without diagnostic metadata.
     *
     * @param conversationId
     *            the conversation identifier
     * @param script
     *            the executed JEXL script
     * @param result
     *            the execution result object
     * @param success
     *            true if execution succeeded, false otherwise
     * @param error
     *            the error message if execution failed
     */
    public ToolExecutionEvent(String conversationId, String script, @Nullable Object result, boolean success,
            @Nullable String error) {
        this(conversationId, null, script, result, success, null, null, null, null, error, null);
    }
}
