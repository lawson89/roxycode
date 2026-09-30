package org.roxycode.app.ai;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.springframework.ai.tool.annotation.Tool;

/**
 * A tool for executing Jexl scripts.
 */
public class JexlTool {

    private final JexlEngine jexl;

    public JexlTool() {
        this.jexl = new JexlBuilder().create();
    }

    /**
     * Executes a Jexl script and returns the result.
     *
     * @param script The Jexl script to execute.
     * @return The result of the execution as a String, or an error message.
     */
    @Tool(description = "Executes a Jexl script and returns the result as a string. Useful for calculations and logic.")
    public String executeJexl(String script) {
        try {
            JexlScript jexlScript = jexl.createScript(script);
            Object result = jexlScript.execute(null);
            if (result == null) {
                return "null";
            }
            return result.toString();
        } catch (Exception e) {
            return "Error executing Jexl script: " + e.getMessage();
        }
    }
}
