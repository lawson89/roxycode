package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/**
 * A tool for executing Jexl scripts. This tool has access to services registered in the
 * JexlServiceRegistry.
 */
public class JexlTool {

    private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
    private static final String ERROR_KEY = "error";
    private static final String MAX_TURNS_EXCEEDED_PREFIX =
            "Autonomous execution stopped: Maximum tool turns (";
    private static final ObjectMapper MAPPER = AiConfig.SHARED_OBJECT_MAPPER;
    private final JexlEngine jexl;
    private final JexlServiceRegistry registry;
    private final WorkflowPhase phase;
    private final int maxTurns;
    private final AtomicInteger turnCount = new AtomicInteger(0);
    private final List<JexlExecutionListener> listeners = new ArrayList<>();
    private boolean returnDirect;

    public JexlTool(JexlServiceRegistry registry, WorkflowPhase phase) {
        this(registry, phase, Integer.MAX_VALUE);
    }

    public JexlTool(JexlServiceRegistry registry, WorkflowPhase phase, int maxTurns) {
        this.jexl = new JexlBuilder().permissions(JexlPermissions.UNRESTRICTED).create();
        this.registry = registry;
        this.phase = phase;
        this.maxTurns = maxTurns;
    }

    public void addListener(JexlExecutionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(JexlExecutionListener listener) {
        listeners.remove(listener);
    }

    public boolean isReturnDirect() {
        return returnDirect;
    }

    public int getTurnCount() {
        return turnCount.get();
    }

    public static boolean isMaxTurnsExceededResponse(String response) {
        return response != null && response.startsWith(MAX_TURNS_EXCEEDED_PREFIX);
    }

    private String formatMaxTurnsExceededMessage() {
        return MAX_TURNS_EXCEEDED_PREFIX + maxTurns + ") exceeded.";
    }

    /**
     * Executes a Jexl script and returns the result.
     *
     * @param script The Jexl script to execute.
     * @return The result of the execution as a String, or an error message.
     */
    @Tool(
            description =
                    "Executes a Jexl script and returns the result as a string. Useful for calculations and logic.")
    public String executeJexl(String script) {
        this.returnDirect = false;
        ExceptionUtils.checkInterrupted("Execution interrupted before start");
        if (maxTurns > 0 && turnCount.get() >= maxTurns) {
            this.returnDirect = true;
            return formatMaxTurnsExceededMessage();
        }
        turnCount.incrementAndGet();
        log.info("Executing JEXL script: {}", script);

        JexlScript jexlScript;
        try {
            jexlScript = jexl.createScript(script);
        } catch (Exception e) {
            log.warn("JEXL Syntax Error intercepted: {}", e.getMessage());
            fireEvent(
                    new JexlExecutionEvent(script, null, false, "Syntax Error: " + e.getMessage()));
            return toJsonError("Syntax Error (Script not executed): " + e.getMessage());
        }

        try {
            JexlContext context = new MapContext(registry.getServices(phase));
            Object result = jexlScript.execute(context);
            ExceptionUtils.checkInterrupted("JEXL execution cancelled");

            if (WorkflowService.isTransitionResponse(result)) {
                this.returnDirect = true;
                fireEvent(new JexlExecutionEvent(script, result, true, null));
                return (String) result;
            }

            fireEvent(new JexlExecutionEvent(script, result, true, null));

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
                    fireEvent(new JexlExecutionEvent(script, null, false, "Execution cancelled"));
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
            fireEvent(new JexlExecutionEvent(script, null, false, error));
            return toJsonError(error);
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

    private void fireEvent(JexlExecutionEvent event) {
        for (JexlExecutionListener listener : listeners) {
            listener.onJexlExecuted(event);
        }
    }
}
