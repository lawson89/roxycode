package org.roxycode.app.events;

/**
 * Event published when an AI agent turn completes.
 *
 * @param conversationId
 *            the conversation identifier
 * @param agentName
 *            the display name of the agent
 * @param totalTurns
 *            the total number of tool turns executed during the request
 * @param result
 *            the final formatted response text
 */
public record AgentTurnCompleteEvent(String conversationId, String agentName, int totalTurns, String result) {
}
