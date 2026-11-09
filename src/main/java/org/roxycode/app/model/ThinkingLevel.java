package org.roxycode.app.model;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.lang.Nullable;

public enum ThinkingLevel {
    DEFAULT("DEFAULT", "Model Default", null), MINIMAL("MINIMAL", "Minimal", GoogleGenAiThinkingLevel.MINIMAL),
    LOW("LOW", "Low", GoogleGenAiThinkingLevel.LOW), MEDIUM("MEDIUM", "Medium", GoogleGenAiThinkingLevel.MEDIUM),
    HIGH("HIGH", "High", GoogleGenAiThinkingLevel.HIGH);

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

    public void applyTo(GoogleGenAiChatOptions.Builder builder) {
        if (builder == null) {
            return;
        }
        if (genAiThinkingLevel != null) {
            builder.thinkingLevel(genAiThinkingLevel);
        }
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
