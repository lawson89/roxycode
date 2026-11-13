package org.roxycode.app.model;

import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Represents an option for selecting a project-level {@link AgentProfile} override in the UI.
 *
 * @param id
 *            the profile identifier (empty string for inheriting the Global Default)
 * @param displayName
 *            the display name shown in the dropdown
 * @param badgeSummary
 *            the pipeline summary shown in the pill badge
 * @param description
 *            the description shown below the selector
 */
public record AgentProfileOption(String id, String displayName, String badgeSummary, String description) {

    public AgentProfileOption {
        id = StringUtils.trimToEmpty(id);
        displayName = Objects.requireNonNullElse(displayName, "");
        badgeSummary = Objects.requireNonNullElse(badgeSummary, "");
        description = Objects.requireNonNullElse(description, "");
    }

    /**
     * Creates an {@link AgentProfileOption} that inherits the global default profile.
     *
     * @param globalProfile
     *            the current global agent profile
     * @return an option representing the global default
     */
    public static AgentProfileOption useGlobalDefault(AgentProfile globalProfile) {
        AgentProfile resolved = Objects.requireNonNullElse(globalProfile, AgentProfile.DEFAULT);
        return new AgentProfileOption("", "Use Global Default (" + resolved.getDisplayName() + ")",
                resolved.getBadgeSummary(),
                "Inherits the default Agent Profile configured in Global Settings (" + resolved.getDisplayName() + " — "
                        + resolved.getBadgeSummary() + "). " + resolved.getDescription());
    }

    /**
     * Creates an {@link AgentProfileOption} from an {@link AgentProfile} enum constant.
     *
     * @param profile
     *            the agent profile
     * @return the corresponding option
     */
    public static AgentProfileOption fromProfile(AgentProfile profile) {
        AgentProfile resolved = Objects.requireNonNullElse(profile, AgentProfile.DEFAULT);
        return new AgentProfileOption(resolved.getId(), resolved.getDisplayName(), resolved.getBadgeSummary(),
                resolved.getDescription());
    }

    @Override
    public String toString() {
        return displayName;
    }
}
