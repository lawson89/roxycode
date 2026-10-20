package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
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
    private static final ObjectMapper MAPPER = AiConfig.SHARED_OBJECT_MAPPER;
    private final JexlEngine jexl;
    private final JexlServiceRegistry registry;
    private final WorkflowPhase phase;
    private final List<JexlExecutionListener> listeners = new ArrayList<>();
    private boolean returnDirect;

    public JexlTool(JexlServiceRegistry registry, WorkflowPhase phase) {
        this.jexl = new JexlBuilder().permissions(JexlPermissions.UNRESTRICTED).create();
        this.registry = registry;
        this.phase = phase;
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
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Execution interrupted before start");
        }
        log.info("Executing JEXL script: {}", script);

        JexlScript jexlScript;
        try {
            jexlScript = jexl.createScript(script);
        } catch (Exception e) {
            log.warn("JEXL Syntax Error intercepted: {}", e.getMessage());
            return toJsonError("Syntax Error (Script not executed): " + e.getMessage());
        }

        try {
            JexlContext context = new MapContext(registry.getServices(phase));
            Object result = jexlScript.execute(context);
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("JEXL execution cancelled");
            }

            if (WorkflowService.isTransitionResponse(result)) {
                this.returnDirect = true;
                try {
                    fireEvent(new JexlExecutionEvent(script, result, true, null));
                } catch (Exception ignored) {
                    // Preserve direct return transition even if turn limit listener throws
                }
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
            if (ExceptionUtils.hasCauseWithMessage(e, "MAX_TOOL_TURNS_EXCEEDED")) {
                throw new RuntimeException("MAX_TOOL_TURNS_EXCEEDED", e);
            }
            if (ExceptionUtils.findCause(e, JexlException.Cancel.class) != null
                    || ExceptionUtils.findCause(e, InterruptedException.class) != null
                    || ExceptionUtils.findCause(e, ClosedByInterruptException.class) != null
                    || ExceptionUtils.findCause(e, CancellationException.class) != null
                    || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                try {
                    fireEvent(new JexlExecutionEvent(script, null, false, "Execution cancelled"));
                } catch (Exception ignored) {
                    // Ignore listener exceptions during cancellation
                }
                CancellationException cancelEx =
                        new CancellationException("JEXL execution cancelled");
                cancelEx.initCause(e);
                throw cancelEx;
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
