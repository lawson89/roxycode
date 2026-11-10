/**
 * Event published when tool execution event.
 */
package org.roxycode.app.events;

public record ToolExecutionEvent(String conversationId, String script, Object result, boolean success, String error) {
}
