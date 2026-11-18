package org.roxycode.app.events;

/** Event published when the AI agent starts a new tool execution turn. */
public record AgentTurnEvent(String conversationId, String agentName, int turnNumber, String phase,
        String scriptSummary) {
}