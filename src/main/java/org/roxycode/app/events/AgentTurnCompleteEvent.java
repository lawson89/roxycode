package org.roxycode.app.events;

/** Event published when an AI agent turn completes. */
public record AgentTurnCompleteEvent(String conversationId, String agentName, int totalTurns, String result) {
}