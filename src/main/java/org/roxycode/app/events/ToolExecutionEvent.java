package org.roxycode.app.events;

/**
 * Event published when a JEXL tool script execution completes.
 *
 * @param conversationId
 *            the conversation identifier
 * @param script
 *            the executed JEXL script
 * @param result
 *            the execution result object
 * @param success
 *            true if execution succeeded, false otherwise
 * @param error
 *            the error message if execution failed
 */
public record ToolExecutionEvent(String conversationId, String script, Object result, boolean success, String error) {
}
