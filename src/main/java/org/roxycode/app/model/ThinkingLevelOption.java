package org.roxycode.app.model;

import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Represents an option for selecting an AI thinking level in the UI.
 *
 * @param id
 *            the unique identifier of the thinking level
 * @param displayName
 *            the display name of the thinking level
 */
public record ThinkingLevelOption(String id, String displayName) {

    public ThinkingLevelOption {
        id = StringUtils.trimToEmpty(id);
        displayName = Objects.requireNonNullElse(displayName, "");
    }

    /**
     * Creates a ThinkingLevelOption that represents using the global default thinking level.
     *
     * @param globalLevel
     *            the current global thinking level
     * @return a ThinkingLevelOption for the global default
     */
    public static ThinkingLevelOption useGlobalDefault(ThinkingLevel globalLevel) {
        ThinkingLevel resolved = Objects.requireNonNullElse(globalLevel, ThinkingLevel.DEFAULT);
        return new ThinkingLevelOption("", "Use Global Default (" + resolved.getDisplayName() + ")");
    }

    /**
     * Creates a ThinkingLevelOption from a ThinkingLevel enum value.
     *
     * @param level
     *            the ThinkingLevel enum value
     * @return a ThinkingLevelOption
     */
    public static ThinkingLevelOption fromThinkingLevel(ThinkingLevel level) {
        ThinkingLevel resolved = Objects.requireNonNullElse(level, ThinkingLevel.DEFAULT);
        return new ThinkingLevelOption(resolved.getId(), resolved.getDisplayName());
    }

    @Override
    public String toString() {
        return displayName;
    }
}
