package org.roxycode.app.ai;

import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * Custom {@link ToolExecutionExceptionProcessor} that rethrows cancellation exceptions and formats
 * root-cause tool errors with a consistent {@code "Error: "} prefix so the LLM can self-correct.
 */
public class RoxyToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    public static final String ERROR_PREFIX = "Error:";

    @Override
    public String process(ToolExecutionException exception) {
        return formatException(exception);
    }

    /**
     * Rethrows cancellation exceptions and formats the root cause message with {@link
     * #ERROR_PREFIX}.
     */
    public static String formatException(Throwable exception) {
        ExceptionUtils.rethrowIfCancelled(exception, "Tool execution cancelled");
        Throwable cause = ExceptionUtils.getRootCause(exception);
        String msg =
                cause != null && cause.getMessage() != null
                        ? cause.getMessage()
                        : (cause != null ? cause.toString() : "Unknown error");
        return msg.startsWith(ERROR_PREFIX) ? msg : ERROR_PREFIX + " " + msg;
    }
}
