package org.roxycode.app.events;

public record AgentTurnEvent(
        String conversationId, String agentName, int turnNumber, String phase) {}
