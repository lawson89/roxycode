package org.roxycode.app.events;

public record AgentTurnCompleteEvent(String agentName, int totalTurns, String result) {}
