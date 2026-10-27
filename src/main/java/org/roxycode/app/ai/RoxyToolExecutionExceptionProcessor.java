package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

public class RoxyToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    private static final String ERROR_KEY = "error";
    private static final String ERROR_JSON_PREFIX =
            """
            {"error":""";

    @Override
    public String process(ToolExecutionException exception) {
        return formatException(exception);
    }

    public static String formatException(Throwable exception) {
        ExceptionUtils.rethrowIfCancelled(exception, "Tool execution cancelled");
        Throwable cause = ExceptionUtils.getRootCause(exception);
        String msg =
                cause != null && cause.getMessage() != null
                        ? cause.getMessage()
                        : (cause != null ? cause.toString() : "Unknown error");
        return toJsonError(AiConfig.SHARED_OBJECT_MAPPER, msg);
    }

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

    public static boolean isErrorResponse(String responseText) {
        return responseText != null && responseText.trim().startsWith(ERROR_JSON_PREFIX);
    }

    public static String stripErrorPrefix(String responseText) {
        if (responseText == null) return "Unknown error";
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
