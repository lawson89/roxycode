package org.roxycode.app.events;

public record AgentTurnEvent(String agentName, int turnNumber, String phase) {}
