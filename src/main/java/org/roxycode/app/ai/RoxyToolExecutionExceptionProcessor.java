package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * Custom {@link ToolExecutionExceptionProcessor} that rethrows cancellation exceptions and formats
 * root-cause tool errors as a JSON object {@code {"error": "..."}} so the LLM can self-correct.
 */
public class RoxyToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    private static final String ERROR_KEY = "error";
    private static final String ERROR_JSON_PREFIX =
            """
            {"error":""";

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
        return toJsonError(AiConfig.SHARED_OBJECT_MAPPER, msg);
    }

    /** Serializes an error message as a JSON object {@code {"error": "..."}}. */
    public static String toJsonError(ObjectMapper mapper, String message) {
        ObjectMapper effectiveMapper = mapper != null ? mapper : AiConfig.SHARED_OBJECT_MAPPER;
        try {
            return effectiveMapper.writeValueAsString(
                    Map.of(ERROR_KEY, message != null ? message : "Unknown error"));
        } catch (Exception e) {
            return """
                    {"error":"Internal Error during error serialization"}"""
                    .strip();
        }
    }

    /** Returns {@code true} if the given tool response text is a JSON error object. */
    public static boolean isErrorResponse(String responseText) {
        return responseText != null && responseText.trim().startsWith(ERROR_JSON_PREFIX);
    }

    /** Extracts the error message from a JSON error response string. */
    public static String stripErrorPrefix(String responseText) {
        if (responseText == null) {
            return "Unknown error";
        }
        String trimmed = responseText.trim();
        if (trimmed.startsWith(ERROR_JSON_PREFIX)) {
            try {
                return AiConfig.SHARED_OBJECT_MAPPER.readTree(trimmed).get(ERROR_KEY).asText();
            } catch (Exception e) {
                return trimmed;
            }
        }
        return trimmed;
    }
}
