package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class JexlTool {

    private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
    private static final String AGENT_NAME = "Roxy";
    private static final String RESULT_KEY = "result";
    public static final String MAX_TURNS_EXCEEDED_PREFIX = "Autonomous execution stopped: Maximum tool turns (";

    private final JexlEngine jexl;
    private final JexlServiceRegistry registry;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper;

    public JexlTool(JexlServiceRegistry registry, @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable ObjectMapper objectMapper, @Nullable SettingsService settingsService) {
        JexlFeatures features = new JexlFeatures().newInstance(false);
        this.jexl = new JexlBuilder().features(features).permissions(AgentJexlPermissions.INSTANCE).strict(true)
                .silent(false).safe(false).create();
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.mapper = objectMapper != null ? objectMapper : AiConfig.SHARED_OBJECT_MAPPER;
        this.settingsService = settingsService;
    }

    public String executeJexl(String script) {
        return executeJexl(script, null);
    }

    @Tool(description = "Executes a Jexl script and returns the result as a string. Useful for workspace file, search, git, and build operations.")
    public String executeJexl(@ToolParam(description = "The Jexl script to execute.") String script,
            ToolContext toolContext) {
        ExceptionUtils.checkInterrupted("Execution interrupted before start");
        TurnToolContext ctx = TurnToolContext.fromToolContext(toolContext);
        String conversationId = ctx.conversationId();
        WorkflowPhase phase = ctx.phase();

        int maxTurns = resolveMaxTurns();
        if (ctx.turnCounter().get() >= maxTurns) {
            String stopMsg = MAX_TURNS_EXCEEDED_PREFIX + maxTurns
                    + ") reached. Stop calling tools and summarize your progress for the user.";
            log.warn("Max tool turns ({}) reached in phase {} for conversation {}", maxTurns, phase, conversationId);
            return toJsonError(stopMsg);
        }

        int currentTurn = ctx.turnCounter().incrementAndGet();
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new AgentTurnEvent(conversationId, AGENT_NAME, currentTurn, phase.name()));
        }

        log.info("Executing JEXL script in phase {} (turn {}/{}): {}", phase, currentTurn, maxTurns, script);

        JexlScript jexlScript;
        try {
            jexlScript = jexl.createScript(script);
        } catch (Exception e) {
            log.warn("JEXL Syntax Error intercepted: {}", e.getMessage());
            publishToolEvent(conversationId, script, null, false, "Syntax Error: " + e.getMessage());
            return toJsonError("Syntax Error (Script not executed): " + e.getMessage());
        }

        try {
            JexlContext context = new MapContext(registry.getServices(phase));
            Object result = jexlScript.execute(context);
            ExceptionUtils.checkInterrupted("JEXL execution cancelled");
            publishToolEvent(conversationId, script, result, true, null);
            return serializeResult(result);
        } catch (Exception e) {
            if (ExceptionUtils.isCancellation(e)) {
                try {
                    publishToolEvent(conversationId, script, null, false, "Execution cancelled");
                } catch (Exception ignored) {
                }
                throw ExceptionUtils.asCancellationException(e, "JEXL execution cancelled");
            }
            log.warn("Error executing JEXL script: {}", e.getMessage());
            Throwable cause = ExceptionUtils.getRootCause(e);
            String error = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            publishToolEvent(conversationId, script, null, false, error);
            return toJsonError(error);
        }
    }

    private int resolveMaxTurns() {
        AppSettings settings = settingsService != null ? settingsService.getSettings() : null;
        return settings != null ? Math.max(1, settings.getMaxAgentToolTurns()) : 5;
    }

    private String serializeResult(@Nullable Object result) throws Exception {
        if (result == null)
            return mapper.writeValueAsString(Map.of(RESULT_KEY, "null"));
        if (result instanceof Map)
            return mapper.writeValueAsString(result);
        if (isScalarResult(result) || AgentJexlPermissions.isAgentServiceClass(result.getClass())) {
            return serializeStringResult(result.toString());
        }
        try {
            return mapper.writeValueAsString(Map.of(RESULT_KEY, result));
        } catch (Exception ex) {
            return serializeStringResult(result.toString());
        }
    }

    private static boolean isScalarResult(Object result) {
        return result instanceof CharSequence || result instanceof Number || result instanceof Boolean
                || result instanceof Character || result instanceof Enum<?>;
    }

    private String serializeStringResult(String rawOutput) throws Exception {
        String output = rawOutput == null || rawOutput.trim().isEmpty() ? "<empty result>" : rawOutput;
        return mapper.writeValueAsString(Map.of(RESULT_KEY, output));
    }

    private void publishToolEvent(String conversationId, String script, Object result, boolean success, String error) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ToolExecutionEvent(conversationId, script, result, success, error));
        }
    }

    private String toJsonError(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message != null ? message : "Unknown error"));
        } catch (Exception e) {
            return """
                    {"error":"Internal Error during error serialization"}""";
        }
    }
}
