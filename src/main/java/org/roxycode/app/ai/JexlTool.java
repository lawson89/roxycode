package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stateless singleton tool for executing JEXL scripts against workspace services registered in the
 * {@link JexlServiceRegistry}.
 */
@Component
public class JexlTool {

    public static final String TOOL_CONTEXT_CONVERSATION_ID = "conversationId";
    public static final String TOOL_CONTEXT_PHASE = "phase";

    private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
    private static final String ERROR_KEY = "error";
    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final ObjectMapper MAPPER = AiConfig.SHARED_OBJECT_MAPPER;

    private final JexlEngine jexl;
    private final JexlServiceRegistry registry;
    private final ApplicationEventPublisher eventPublisher;

    public JexlTool(JexlServiceRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public JexlTool(
            JexlServiceRegistry registry, @Nullable ApplicationEventPublisher eventPublisher) {
        this.jexl =
                new JexlBuilder()
                        .permissions(AgentJexlPermissions.INSTANCE)
                        .strict(true)
                        .silent(false)
                        .safe(false)
                        .create();
        this.registry = registry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Convenience overload for executing a JEXL script in default EXPLORE context (useful for unit
     * testing).
     *
     * @param script The JEXL script to execute.
     * @return The result of the execution as a JSON String, or an error message.
     */
    public String executeJexl(String script) {
        return executeJexl(script, null);
    }

    /**
     * Executes a Jexl script and returns the result.
     *
     * @param script The Jexl script to execute.
     * @param toolContext The Spring AI tool context containing conversationId and phase.
     * @return The result of the execution as a String, or an error message.
     */
    @Tool(
            description =
                    "Executes a Jexl script and returns the result as a string. Useful for workspace file, search, git, and build operations.")
    public String executeJexl(
            @ToolParam(description = "The Jexl script to execute.") String script,
            ToolContext toolContext) {
        ExceptionUtils.checkInterrupted("Execution interrupted before start");
        String conversationId = resolveConversationId(toolContext);
        WorkflowPhase phase = resolvePhase(toolContext);
        log.info("Executing JEXL script in phase {}: {}", phase, script);

        JexlScript jexlScript;
        try {
            jexlScript = jexl.createScript(script);
        } catch (Exception e) {
            log.warn("JEXL Syntax Error intercepted: {}", e.getMessage());
            publishToolEvent(
                    conversationId, script, null, false, "Syntax Error: " + e.getMessage());
            return toJsonError("Syntax Error (Script not executed): " + e.getMessage());
        }

        try {
            JexlContext context = new MapContext(registry.getServices(phase));
            Object result = jexlScript.execute(context);
            ExceptionUtils.checkInterrupted("JEXL execution cancelled");

            publishToolEvent(conversationId, script, result, true, null);

            if (result instanceof Map) {
                return MAPPER.writeValueAsString(result);
            }

            String output = result == null ? "null" : result.toString();
            if (output.trim().isEmpty()) {
                output = "<empty result>";
            }
            return MAPPER.writeValueAsString(Map.of("result", output));
        } catch (Exception e) {
            if (ExceptionUtils.isCancellation(e)) {
                try {
                    publishToolEvent(conversationId, script, null, false, "Execution cancelled");
                } catch (Exception ignored) {
                    // Ignore listener exceptions during cancellation
                }
                throw ExceptionUtils.asCancellationException(e, "JEXL execution cancelled");
            }

            log.error("Error executing JEXL script: {}", e.getMessage(), e);
            Throwable cause = ExceptionUtils.getRootCause(e);
            String error = cause.getMessage();
            if (error == null) {
                error = cause.toString();
            }
            publishToolEvent(conversationId, script, null, false, error);
            return toJsonError(error);
        }
    }

    private String resolveConversationId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object convId = toolContext.getContext().get(TOOL_CONTEXT_CONVERSATION_ID);
            if (convId instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return DEFAULT_CONVERSATION_ID;
    }

    private WorkflowPhase resolvePhase(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object phaseObj = toolContext.getContext().get(TOOL_CONTEXT_PHASE);
            if (phaseObj instanceof WorkflowPhase wp) {
                return wp;
            }
            if (phaseObj instanceof String s) {
                try {
                    return WorkflowPhase.valueOf(s);
                } catch (IllegalArgumentException ignored) {
                    // Fallback to default
                }
            }
        }
        return WorkflowPhase.EXPLORE;
    }

    private void publishToolEvent(
            String conversationId, String script, Object result, boolean success, String error) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(
                    new ToolExecutionEvent(conversationId, script, result, success, error));
        }
    }

    private String toJsonError(String message) {
        try {
            return MAPPER.writeValueAsString(
                    Map.of(ERROR_KEY, message != null ? message : "Unknown error"));
        } catch (Exception ex) {
            return "{error: Internal Error during error serialization}";
        }
    }
}
