package org.roxycode.app.events;

import org.roxycode.app.ai.WorkflowPhase;

/**
 * Event published when token usage and cost telemetry are recorded for an AI model call.
 */
public record TokenUsageEvent(String conversationId, String modelName, int contextWindowTokens, int promptTokens,
        int cachedInputTokens, int uncachedInputTokens, int candidatesTokens, int thoughtsTokens, int totalTokens,
        WorkflowPhase phase, double uncachedInputCostUsd, double cachedInputCostUsd, double outputCostUsd,
        double thoughtsCostUsd, double estimatedCostUsd) {
}
