/**
 * Event published when agent turn complete event.
 */
package org.roxycode.app.events;

public record AgentTurnCompleteEvent(String conversationId, String agentName, int totalTurns, String result) {
}
