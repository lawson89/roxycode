package org.roxycode.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.ToolExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for tool execution events and logs every invocation as a single-line JSONL record.
 */
@Component
public class ToolCallLoggingListener {
    public static final String TOOL_CALLS_LOGGER_NAME = "org.roxycode.toolcalls";
    private final ObjectMapper mapper;
    private final Logger toolCallLogger;

    /**
     * Creates a listener using the Spring-managed {@link ObjectMapper}.
     *
     * @param objectMapper
     *            the Jackson ObjectMapper, or null to fall back to the shared mapper
     */
    @Autowired
    public ToolCallLoggingListener(@Nullable ObjectMapper objectMapper) {
        this(objectMapper, LoggerFactory.getLogger(TOOL_CALLS_LOGGER_NAME));
    }

    ToolCallLoggingListener(@Nullable ObjectMapper objectMapper, Logger toolCallLogger) {
        this.mapper = Objects.requireNonNullElse(objectMapper, AiConfig.SHARED_OBJECT_MAPPER);
        this.toolCallLogger = Objects.requireNonNull(toolCallLogger, "toolCallLogger must not be null");
    }

    /**
     * Serializes the {@link ToolExecutionEvent} into a single-line JSON object and writes it to the tool calls logger.
     *
     * @param event
     *            the completed tool execution event
     */
    @EventListener
    public void onToolExecution(@Nullable ToolExecutionEvent event) {
        if (event == null) {
            return;
        }
        String jsonLine = serializeEvent(event);
        if (event.success()) {
            toolCallLogger.info(jsonLine);
        } else {
            toolCallLogger.warn(jsonLine);
        }
    }

    String serializeEvent(ToolExecutionEvent event) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("conversationId", Objects.requireNonNullElse(event.conversationId(), "unknown"));
        record.put("phase", event.phase());
        record.put("success", event.success());
        record.put("errorCategory", event.errorCategory());
        record.put("line", event.line());
        record.put("column", event.column());
        record.put("offendingLine", event.offendingLine());
        record.put("error", event.error());
        record.put("hint", event.hint());
        record.put("script", StringUtils.defaultString(event.script()));
        record.put("result", event.result());

        try {
            return mapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            record.put("result", String.valueOf(event.result()));
            try {
                return mapper.writeValueAsString(record);
            } catch (JsonProcessingException fallbackEx) {
                return "{\"timestamp\":\"" + Instant.now()
                        + "\",\"success\":false,\"error\":\"Failed to serialize tool call log\"}";
            }
        }
    }
}
