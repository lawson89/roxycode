package org.roxycode.app.events;

/**
 * Event published when the AI agent updates its real-time activity status during a turn.
 *
 * @param conversationId
 *            the conversation identifier
 * @param statusText
 *            the current activity status message
 */
public record AgentActivityEvent(String conversationId, String statusText) {
}
