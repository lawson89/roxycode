package org.roxycode.app.ai;

import java.util.Map;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * Custom {@link ToolExecutionExceptionProcessor} that rethrows cancellation exceptions and formats
 * root-cause tool errors as a JSON object {@code {"error": "..."}} so the LLM can self-correct.
 */
public class RoxyToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    @Override
    public String process(ToolExecutionException exception) {
        return formatException(exception);
    }

    /** Rethrows cancellation exceptions and formats the root cause message as a JSON object. */
    public static String formatException(Throwable exception) {
        ExceptionUtils.rethrowIfCancelled(exception, "Tool execution cancelled");
        Throwable cause = ExceptionUtils.getRootCause(exception);
        String msg =
                cause != null && cause.getMessage() != null
                        ? cause.getMessage()
                        : (cause != null ? cause.toString() : "Unknown error");

        try {
            return AiConfig.SHARED_OBJECT_MAPPER.writeValueAsString(Map.of("error", msg));
        } catch (Exception e) {
            // Minimal fallback if Jackson fails
            return "{\"error\":\"" + msg.replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
        }
    }

    /** Returns {@code true} if the given tool response text is a JSON error object. */
    public static boolean isErrorResponse(String responseText) {
        return responseText != null && responseText.trim().startsWith("{\"error\":");
    }

    /** Extracts the error message from a JSON error response string. */
    public static String stripErrorPrefix(String responseText) {
        if (responseText == null) {
            return "Unknown error";
        }
        String trimmed = responseText.trim();
        if (trimmed.startsWith("{\"error\":")) {
            try {
                return AiConfig.SHARED_OBJECT_MAPPER.readTree(trimmed).get("error").asText();
            } catch (Exception e) {
                return trimmed;
            }
        }
        return trimmed;
    }
}
