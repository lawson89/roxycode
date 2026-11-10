package org.roxycode.app.events;

/**
 * Event published when the AI agent starts a new tool execution turn.
 *
 * @param conversationId
 *            the conversation identifier
 * @param agentName
 *            the display name of the agent
 * @param turnNumber
 *            the 1-based tool turn number
 * @param phase
 *            the active workflow phase name
 * @param scriptSummary
 *            a brief summary of the JEXL script being executed
 */
public record AgentTurnEvent(String conversationId, String agentName, int turnNumber, String phase,
        String scriptSummary) {

    public AgentTurnEvent(String conversationId, String agentName, int turnNumber, String phase) {
        this(conversationId, agentName, turnNumber, phase, "");
    }
}
