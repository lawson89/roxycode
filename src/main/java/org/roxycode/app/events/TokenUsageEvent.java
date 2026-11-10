/**
 * Event published when token usage event.
 */
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

}
