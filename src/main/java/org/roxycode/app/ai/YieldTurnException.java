package org.roxycode.app.ai;

/**
 * Exception thrown to immediately stop the current agent turn and yield control back to the user.
 * The message typically contains content (like a proposal or plan) to be displayed to the user.
 */
public class YieldTurnException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public YieldTurnException(String message) {
    super(message);
  }

  public YieldTurnException(String message, Throwable cause) {
    super(message, cause);
  }
}
