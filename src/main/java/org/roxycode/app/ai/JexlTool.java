package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/**
 * A tool for executing Jexl scripts. This tool has access to services registered in the
 * JexlServiceRegistry.
 */
public class JexlTool {

  private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
  private final JexlEngine jexl;
  private final JexlServiceRegistry registry;
  private final WorkflowPhase phase;
  private final List<JexlExecutionListener> listeners = new ArrayList<>();

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
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Execution interrupted before start");
    }
    log.info("Executing JEXL script: {}", script);
    ObjectMapper mapper = new ObjectMapper();

    // Prevent further execution if a transition is already pending in the Task lifecycle
    if (phase != WorkflowPhase.EXPLORE) {
      WorkflowService workflowService =
          (WorkflowService) registry.getServices().get("workflowService");
      if (workflowService != null && workflowService.getPendingPhase() != null) {
        return "{\\\"error\\\": \\\"A phase transition is already pending. Please wait for the user to approve or reject the transition before performing more actions.\\\"}";
      }
    }

    JexlScript jexlScript;
    try {
      jexlScript = jexl.createScript(script);
    } catch (Exception e) {
      log.warn("JEXL Syntax Error intercepted: {}", e.getMessage());
      return "{\\\"error\\\": \\\"Syntax Error (Script not executed): "
          + StringEscapeUtils.escapeJson(e.getMessage())
          + "\\\"}";
    }

    try {
      JexlContext context = new MapContext(registry.getServices(phase));
      Object result = jexlScript.execute(context);

      fireEvent(new JexlExecutionEvent(script, result, true, null));

      if (result instanceof Map) {
        return mapper.writeValueAsString(result);
      }

      String output = result == null ? "null" : result.toString();
      if (output.trim().isEmpty()) {
        output = "<empty result>";
      }
      return mapper.writeValueAsString(Map.of("result", output));
    } catch (Exception e) {
      // Check if this is a YieldTurnException wrapping or direct
      Throwable current = e;
      while (current != null) {
        if (current instanceof YieldTurnException) {
          // YieldTurnException message is already a JSON string
          return current.getMessage();
        }
        if (current instanceof InterruptedException || current instanceof CancellationException) {
          if (current instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          throw new RuntimeException(current);
        }
        if (Objects.equals(current.getCause(), current) || current.getCause() == null) break;
        current = current.getCause();
      }

      log.error("Error executing JEXL script: {}", e.getMessage(), e);
      Throwable cause = e;
      while (cause.getCause() != null && !Objects.equals(cause.getCause(), cause)) {
        cause = cause.getCause();
      }
      String error = cause.getMessage();
      if (error == null) {
        error = cause.toString();
      }
      fireEvent(new JexlExecutionEvent(script, null, false, error));
      try {
        return mapper.writeValueAsString(Map.of("error", error));
      } catch (Exception ex) {
        return "{\\\"error\\\": \\\"Internal Error during error serialization\\\"}";
      }
    }
  }

  private void fireEvent(JexlExecutionEvent event) {
    for (JexlExecutionListener listener : listeners) {
      listener.onJexlExecuted(event);
    }
  }
}
