package org.roxycode.app.events;

import org.roxycode.app.ai.WorkflowPhase;

public record TokenUsageEvent(String conversationId, String modelName, int contextWindowTokens, int promptTokens,
        int cachedInputTokens, int uncachedInputTokens, int candidatesTokens, int thoughtsTokens, int totalTokens,
        WorkflowPhase phase, double uncachedInputCostUsd, double cachedInputCostUsd, double outputCostUsd,
        double thoughtsCostUsd, double estimatedCostUsd) {

    public TokenUsageEvent(String conversationId, String modelName, int contextWindowTokens, int promptTokens,
            int cachedInputTokens, int uncachedInputTokens, int candidatesTokens, int thoughtsTokens, int totalTokens,
            double estimatedCostUsd) {
        this(conversationId, modelName, contextWindowTokens, promptTokens, cachedInputTokens, uncachedInputTokens,
                candidatesTokens, thoughtsTokens, totalTokens, null, 0.0, 0.0, 0.0, 0.0, estimatedCostUsd);
    }

    public int outputAndThinkingTokens() {
        return Math.max(0, candidatesTokens) + Math.max(0, thoughtsTokens);
    }

    public double inputCostUsd() {
        return Math.max(0.0, uncachedInputCostUsd) + Math.max(0.0, cachedInputCostUsd);
    }

    public double outputAndThinkingCostUsd() {
        return Math.max(0.0, outputCostUsd) + Math.max(0.0, thoughtsCostUsd);
    }
}
