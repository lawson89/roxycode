package org.roxycode.app.events;

import java.util.List;
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
 * @param modelName
 *            the Gemini model name used for the turn, or null if unavailable
 * @param turnNumber
 *            the 1-based tool turn number within the conversation turn, or null if unavailable
 * @param durationMs
 *            the execution duration in milliseconds, or null if unavailable
 * @param syntaxStage
 *            true if the failure occurred during script parsing/compilation
 * @param exceptionType
 *            the fully qualified exception class name on failure, or null
 * @param rootCauseType
 *            the fully qualified root cause class name on failure, or null
 * @param causeChain
 *            the bounded exception cause chain on failure, or null
 * @param appStackFrames
 *            the top application stack frames on failure, or null
 */
public record ToolExecutionEvent(String conversationId, @Nullable String phase, String script, @Nullable Object result,
        boolean success, @Nullable String errorCategory, @Nullable Integer line, @Nullable Integer column,
        @Nullable String offendingLine, @Nullable String error, @Nullable String hint, @Nullable String modelName,
        @Nullable Integer turnNumber, @Nullable Long durationMs, boolean syntaxStage, @Nullable String exceptionType,
        @Nullable String rootCauseType, @Nullable List<String> causeChain, @Nullable List<String> appStackFrames) {

    /**
     * Backward-compatible convenience constructor without extended diagnostic metadata.
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
    public ToolExecutionEvent(String conversationId, @Nullable String phase, String script, @Nullable Object result,
            boolean success, @Nullable String errorCategory, @Nullable Integer line, @Nullable Integer column,
            @Nullable String offendingLine, @Nullable String error, @Nullable String hint) {
        this(conversationId, phase, script, result, success, errorCategory, line, column, offendingLine, error, hint,
                null, null, null, false, null, null, null, null);
    }

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
        this(conversationId, null, script, result, success, null, null, null, null, error, null, null, null, null,
                false, null, null, null, null);
    }
}
