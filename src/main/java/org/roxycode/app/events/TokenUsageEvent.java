package org.roxycode.app.events;

public record TokenUsageEvent(String conversationId, String modelName, int contextWindowTokens, int promptTokens,
        int cachedInputTokens, int uncachedInputTokens, int candidatesTokens, int thoughtsTokens, int totalTokens,
        double estimatedCostUsd) {

    public int outputAndThinkingTokens() {
        return Math.max(0, candidatesTokens) + Math.max(0, thoughtsTokens);
    }
}
