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
 * Listens for tool execution events, logs every invocation to {@code toolcalls.jsonl}, and logs failed invocations with
 * full diagnostic context to {@code jexl-errors.jsonl}.
 */
@Component
@SuppressWarnings("PMD.MoreThanOneLogger")
public class ToolCallLoggingListener {
    public static final String TOOL_CALLS_LOGGER_NAME = "org.roxycode.toolcalls";
    public static final String JEXL_ERRORS_LOGGER_NAME = "org.roxycode.jexl.errors";
    private final ObjectMapper mapper;
    private final Logger toolCallLogger;
    private final Logger jexlErrorsLogger;

    /**
     * Creates a listener using the Spring-managed {@link ObjectMapper}.
     *
     * @param objectMapper
     *            the Jackson ObjectMapper, or null to fall back to the shared mapper
     */
    @Autowired
    public ToolCallLoggingListener(@Nullable ObjectMapper objectMapper) {
        this(objectMapper, LoggerFactory.getLogger(TOOL_CALLS_LOGGER_NAME),
                LoggerFactory.getLogger(JEXL_ERRORS_LOGGER_NAME));
    }

    ToolCallLoggingListener(@Nullable ObjectMapper objectMapper, Logger toolCallLogger) {
        this(objectMapper, toolCallLogger, LoggerFactory.getLogger(JEXL_ERRORS_LOGGER_NAME));
    }

    ToolCallLoggingListener(@Nullable ObjectMapper objectMapper, Logger toolCallLogger, Logger jexlErrorsLogger) {
        this.mapper = Objects.requireNonNullElse(objectMapper, AiConfig.SHARED_OBJECT_MAPPER);
        this.toolCallLogger = Objects.requireNonNull(toolCallLogger, "toolCallLogger must not be null");
        this.jexlErrorsLogger = Objects.requireNonNull(jexlErrorsLogger, "jexlErrorsLogger must not be null");
    }

    /**
     * Serializes the {@link ToolExecutionEvent} into a single-line JSON object and writes it to the tool calls logger,
     * and writes failed executions with rich diagnostics to the JEXL errors logger.
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
            jexlErrorsLogger.warn(serializeErrorEvent(event));
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

    String serializeErrorEvent(ToolExecutionEvent event) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("conversationId", Objects.requireNonNullElse(event.conversationId(), "unknown"));
        record.put("phase", event.phase());
        record.put("modelName", event.modelName());
        record.put("turnNumber", event.turnNumber());
        record.put("durationMs", event.durationMs());
        record.put("syntaxStage", event.syntaxStage());
        record.put("errorCategory", event.errorCategory());
        record.put("exceptionType", event.exceptionType());
        record.put("rootCauseType", event.rootCauseType());
        record.put("causeChain", event.causeChain());
        record.put("appStackFrames", event.appStackFrames());
        record.put("line", event.line());
        record.put("column", event.column());
        record.put("offendingLine", event.offendingLine());
        record.put("error", event.error());
        record.put("hint", event.hint());
        record.put("script", StringUtils.defaultString(event.script()));

        try {
            return mapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            return "{\"timestamp\":\"" + Instant.now() + "\",\"error\":\"Failed to serialize jexl error log\"}";
        }
    }
}
