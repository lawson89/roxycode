package org.roxycode.app.model;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.lang.Nullable;

/** Configurable Gemini thinking levels, including phase- and sub-turn-aware adaptive thinking. */
public enum ThinkingLevel {
    DEFAULT("DEFAULT", "Model Default", null), ADAPTIVE("ADAPTIVE", "Adaptive (Phase-Aware)", null),
    LOW("LOW", "Low", GoogleGenAiThinkingLevel.LOW), MEDIUM("MEDIUM", "Medium", GoogleGenAiThinkingLevel.MEDIUM),
    HIGH("HIGH", "High", GoogleGenAiThinkingLevel.HIGH);

    private final String id;
    private final String displayName;
    @Nullable
    private final GoogleGenAiThinkingLevel genAiThinkingLevel;

    ThinkingLevel(String id, String displayName, @Nullable GoogleGenAiThinkingLevel genAiThinkingLevel) {
        this.id = id;
        this.displayName = displayName;
        this.genAiThinkingLevel = genAiThinkingLevel;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public GoogleGenAiThinkingLevel getGenAiThinkingLevel() {
        return genAiThinkingLevel;
    }

    public void applyTo(GoogleGenAiChatOptions.Builder builder) {
        if (builder != null && genAiThinkingLevel != null) {
            builder.thinkingLevel(genAiThinkingLevel);
        }
    }

    /** Resolves the concrete sub-turn thinking level for a model call when {@link #ADAPTIVE} is active. */
    public ThinkingLevel resolveSubTurnLevel(@Nullable WorkflowPhase phase, @Nullable String modelName,
            boolean planSubmitted, boolean hasToolResponses, boolean lastToolCallFailed) {
        if (this != ADAPTIVE) {
            return this;
        }
        if (phase == null) {
            return MEDIUM;
        }
        return switch (phase) {
            case PLAN -> resolvePlanLevel(modelName, planSubmitted, hasToolResponses, lastToolCallFailed);
            case CODE -> {
                if (lastToolCallFailed) {
                    yield HIGH;
                }
                yield MEDIUM;
            }
            case REVIEW -> HIGH;
            case EXPLORE -> {
                if (hasToolResponses) {
                    yield MEDIUM;
                }
                yield LOW;
            }
        };
    }

    private static ThinkingLevel resolvePlanLevel(@Nullable String modelName, boolean planSubmitted,
            boolean hasToolResponses, boolean lastToolCallFailed) {
        if (planSubmitted) {
            return LOW;
        }
        if (isCarbonModel(modelName)) {
            if (lastToolCallFailed) {
                return HIGH;
            }
            if (hasToolResponses) {
                return MEDIUM;
            }
            return LOW;
        }
        if (hasToolResponses) {
            return HIGH;
        }
        return MEDIUM;
    }

    static boolean isCarbonModel(@Nullable String modelName) {
        return modelName != null && modelName.toLowerCase(Locale.ROOT).contains("carbon");
    }

    public static ThinkingLevel fromString(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return DEFAULT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(normalized) || "MINIMAL".equals(normalized)) {
            return LOW;
        }
        for (ThinkingLevel level : values()) {
            if (level.id.equalsIgnoreCase(normalized) || level.displayName.equalsIgnoreCase(normalized)) {
                return level;
            }
        }
        return DEFAULT;
    }

    /** Resolves a {@link ThinkingLevel} from a Spring AI {@link GoogleGenAiThinkingLevel}. */
    public static ThinkingLevel fromGenAiThinkingLevel(@Nullable GoogleGenAiThinkingLevel genAiLevel) {
        if (genAiLevel == null) {
            return DEFAULT;
        }
        for (ThinkingLevel level : values()) {
            if (level.genAiThinkingLevel == genAiLevel) {
                return level;
            }
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return displayName;
    }
}