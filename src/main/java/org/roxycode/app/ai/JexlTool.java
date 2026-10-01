package org.roxycode.app.ai;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import java.util.ArrayList;
import java.util.List;

/**
 * A tool for executing Jexl scripts.
 */
public class JexlTool {

    private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
    private final JexlEngine jexl;
    private final List<JexlExecutionListener> listeners = new ArrayList<>();

    public JexlTool() {
        this.jexl = new JexlBuilder().create();
    }

    public void addListener(JexlExecutionListener listener) {
        listeners.add(listener);
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
        try {
            JexlScript jexlScript = jexl.createScript(script);
            Object result = jexlScript.execute(null);
            String output = result == null ? "null" : result.toString();
            fireEvent(new JexlExecutionEvent(script, result, true, null));
            return output;
        } catch (Exception e) {
            log.error("Error executing JEXL script: {}", e.getMessage(), e);
            String error = e.getMessage();
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