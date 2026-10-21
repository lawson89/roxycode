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

    /** Returns {@code true} if the given tool response text starts with {@link #ERROR_PREFIX}. */
    public static boolean isErrorResponse(String responseText) {
        return responseText != null && responseText.trim().startsWith(ERROR_PREFIX);
    }

    /** Strips {@link #ERROR_PREFIX} from a formatted error response string. */
    public static String stripErrorPrefix(String responseText) {
        if (responseText == null) {
            return "Unknown error";
        }
        String trimmed = responseText.trim();
        if (trimmed.startsWith(ERROR_PREFIX)) {
            return trimmed.substring(ERROR_PREFIX.length()).trim();
        }
        return trimmed;
    }
}
