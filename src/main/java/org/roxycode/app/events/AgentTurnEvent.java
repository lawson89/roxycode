/**
 * Event published when agent turn event.
 */
package org.roxycode.app.events;

public record AgentTurnEvent(String conversationId, String agentName, int turnNumber, String phase,
        String scriptSummary) {

    public AgentTurnEvent(String conversationId, String agentName, int turnNumber, String phase) {
        this(conversationId, agentName, turnNumber, phase, "");
    }
}
