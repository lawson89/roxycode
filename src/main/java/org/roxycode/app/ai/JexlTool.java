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
      YieldTurnException yieldEx = YieldTurnException.findCause(e, YieldTurnException.class);
      if (yieldEx != null) {
        try {
          fireEvent(new JexlExecutionEvent(script, yieldEx.getMessage(), true, null));
        } catch (Exception ignored) {
          // Preserve YieldTurnException even if turn limit listener throws
        }
        throw new YieldTurnException(yieldEx.getMessage(), e);
      }
      if (YieldTurnException.hasCauseWithMessage(e, "MAX_TOOL_TURNS_EXCEEDED")) {
        throw new RuntimeException("MAX_TOOL_TURNS_EXCEEDED", e);
      }
      if (YieldTurnException.findCause(e, JexlException.Cancel.class) != null
          || YieldTurnException.findCause(e, InterruptedException.class) != null
          || YieldTurnException.findCause(e, ClosedByInterruptException.class) != null
          || YieldTurnException.findCause(e, CancellationException.class) != null
          || Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        try {
          fireEvent(new JexlExecutionEvent(script, null, false, "Execution cancelled"));
        } catch (Exception ignored) {
          // Ignore listener exceptions during cancellation
        }
        CancellationException cancelEx = new CancellationException("JEXL execution cancelled");
        cancelEx.initCause(e);
        throw cancelEx;
      }

      log.error("Error executing JEXL script: {}", e.getMessage(), e);
      Throwable cause = YieldTurnException.getRootCause(e);
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
