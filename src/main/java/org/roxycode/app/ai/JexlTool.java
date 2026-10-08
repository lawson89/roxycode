package org.roxycode.app.ai;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool for executing Jexl scripts. This tool has access to services registered in the JexlServiceRegistry.
 */
public class JexlTool {

    private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
    private final JexlEngine jexl;
    private final JexlServiceRegistry registry;
    private final List<JexlExecutionListener> listeners = new ArrayList<>();

    public JexlTool(JexlServiceRegistry registry) {
        this.jexl = new JexlBuilder()
                .permissions(JexlPermissions.UNRESTRICTED)
                .create();
        this.registry = registry;
    }

    public void addListener(JexlExecutionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(JexlExecutionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Executes a Jexl script and returns the result.
     *
     * @param script The Jexl script to execute.
     * @return The result of the execution as a String, or an error message.
     */
    @Tool(description = "Executes a Jexl script and returns the result as a string. Useful for calculations and logic.")
    public String executeJexl(String script) {
        log.info("Executing JEXL script: {}", script);
        JexlScript jexlScript;
        try {
            jexlScript = jexl.createScript(script);
        } catch (Exception e) {
            log.warn("JEXL Syntax Error intercepted: {}", e.getMessage());
            return "Syntax Error (Script not executed): " + e.getMessage();
        }

                try {
            JexlContext context = new MapContext(registry.getServices());
            Object result = jexlScript.execute(context);
            String output = result == null ? "null" : result.toString();
            if (output.trim().isEmpty()) {
                output = "<empty result>";
            }
            fireEvent(new JexlExecutionEvent(script, result, true, null));
            return output;
        } catch (Exception e) {
            // Check if this is a YieldTurnException wrapping or direct
            Throwable current = e;
            while (current != null) {
                if (current instanceof YieldTurnException) {
                    throw (YieldTurnException) current;
                }
                if (current.getCause() == current) break;
                current = current.getCause();
            }

            log.error("Error executing JEXL script: {}", e.getMessage(), e);
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String error = cause.getMessage();
            if (error == null) {
                error = cause.toString();
            }
            fireEvent(new JexlExecutionEvent(script, null, false, error));
            return "Error executing Jexl script: " + error;
        }
    }

    private void fireEvent(JexlExecutionEvent event) {
        for (JexlExecutionListener listener : listeners) {
            listener.onJexlExecuted(event);
        }
    }
}