package org.roxycode.app.model;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.lang.Nullable;

/**
 * Represents configurable Gemini thinking levels, including phase- and sub-turn-aware adaptive thinking.
 */
public enum ThinkingLevel {
    DEFAULT("DEFAULT", "Model Default", null), ADAPTIVE("ADAPTIVE", "Adaptive (Phase-Aware)", null),
    MINIMAL("MINIMAL", "Minimal", GoogleGenAiThinkingLevel.MINIMAL), LOW("LOW", "Low", GoogleGenAiThinkingLevel.LOW),
    MEDIUM("MEDIUM", "Medium", GoogleGenAiThinkingLevel.MEDIUM), HIGH("HIGH", "High", GoogleGenAiThinkingLevel.HIGH);

    private static final String ALIAS_NONE = "NONE";

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
        if (builder == null) {
            return;
        }
        if (genAiThinkingLevel != null) {
            builder.thinkingLevel(genAiThinkingLevel);
        }
    }

    /**
     * Resolves the concrete sub-turn thinking level for a model call. When this level is {@link #ADAPTIVE}, the
     * thinking budget is chosen dynamically based on the active workflow phase and sub-turn state:
     * <ul>
     * <li>{@code PLAN}: {@link #HIGH} while designing the blueprint, dropping to {@link #LOW} after a plan is
     * submitted.</li>
     * <li>{@code CODE}: {@link #LOW} by default for fast mechanical execution, escalating to {@link #HIGH} when the
     * previous tool execution reported a failure.</li>
     * <li>{@code REVIEW}: {@link #MEDIUM}.</li>
     * <li>{@code EXPLORE}: {@link #LOW} on initial tool discovery, rising to {@link #MEDIUM} after tool outputs are
     * returned for synthesis.</li>
     * </ul>
     *
     * @param phase
     *            the current workflow phase
     * @param planSubmitted
     *            whether an implementation plan is currently submitted
     * @param hasToolResponses
     *            whether the prompt already contains tool execution responses
     * @param lastToolCallFailed
     *            whether the most recent tool execution response contains a failure or error
     * @return the concrete {@link ThinkingLevel} to apply to the model call
     */
    public ThinkingLevel resolveSubTurnLevel(@Nullable WorkflowPhase phase, boolean planSubmitted,
            boolean hasToolResponses, boolean lastToolCallFailed) {
        if (this != ADAPTIVE) {
            return this;
        }
        if (phase == null) {
            return MEDIUM;
        }
        return switch (phase) {
            case PLAN -> {
                if (planSubmitted) {
                    yield LOW;
                }
                yield HIGH;
            }
            case CODE -> {
                if (lastToolCallFailed) {
                    yield HIGH;
                }
                yield LOW;
            }
            case REVIEW -> MEDIUM;
            case EXPLORE -> {
                if (hasToolResponses) {
                    yield MEDIUM;
                }
                yield LOW;
            }
        };
    }

    public static ThinkingLevel fromString(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return DEFAULT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (ALIAS_NONE.equals(normalized)) {
            return MINIMAL;
        }
        for (ThinkingLevel level : values()) {
            if (level.id.equalsIgnoreCase(normalized) || level.displayName.equalsIgnoreCase(normalized)) {
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
