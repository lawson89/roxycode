package org.roxycode.app.events;

/** Event published when the AI agent updates its real-time activity status during a turn. */
public record AgentActivityEvent(String conversationId, String statusText) {
}