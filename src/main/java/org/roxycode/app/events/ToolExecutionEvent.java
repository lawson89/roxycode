package org.roxycode.app.events;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Event published when a JEXL tool script execution completes. */
public record ToolExecutionEvent(String conversationId, @Nullable String phase, String script, @Nullable Object result,
        boolean success, @Nullable String errorCategory, @Nullable Integer line, @Nullable Integer column,
        @Nullable String offendingLine, @Nullable String error, @Nullable String hint, @Nullable String modelName,
        @Nullable Integer turnNumber, @Nullable Long durationMs, boolean syntaxStage, @Nullable String exceptionType,
        @Nullable String rootCauseType, @Nullable List<String> causeChain, @Nullable List<String> appStackFrames) {

    /** Convenience constructor without extended diagnostic metadata. */
    public ToolExecutionEvent(String conversationId, @Nullable String phase, String script, @Nullable Object result,
            boolean success, @Nullable String errorCategory, @Nullable Integer line, @Nullable Integer column,
            @Nullable String offendingLine, @Nullable String error, @Nullable String hint) {
        this(conversationId, phase, script, result, success, errorCategory, line, column, offendingLine, error, hint,
                null, null, null, false, null, null, null, null);
    }

    /** Convenience constructor without diagnostic metadata. */
    public ToolExecutionEvent(String conversationId, String script, @Nullable Object result, boolean success,
            @Nullable String error) {
        this(conversationId, null, script, result, success, null, null, null, null, error, null, null, null, null,
                false, null, null, null, null);
    }
}