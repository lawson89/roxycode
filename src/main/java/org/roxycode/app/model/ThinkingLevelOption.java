package org.roxycode.app.model;

import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

public record ThinkingLevelOption(String id, String displayName) {

    public ThinkingLevelOption {
        id = StringUtils.trimToEmpty(id);
        displayName = Objects.requireNonNullElse(displayName, "");
    }

    public static ThinkingLevelOption useGlobalDefault(ThinkingLevel globalLevel) {
        ThinkingLevel resolved = Objects.requireNonNullElse(globalLevel, ThinkingLevel.DEFAULT);
        return new ThinkingLevelOption("", "Use Global Default (" + resolved.getDisplayName() + ")");
    }

    public static ThinkingLevelOption fromThinkingLevel(ThinkingLevel level) {
        ThinkingLevel resolved = Objects.requireNonNullElse(level, ThinkingLevel.DEFAULT);
        return new ThinkingLevelOption(resolved.getId(), resolved.getDisplayName());
    }

    @Override
    public String toString() {
        return displayName;
    }
}
