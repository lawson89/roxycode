package org.roxycode.app.model;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.springframework.lang.Nullable;

/**
 * Represents the 3-Profile Agent Profile system (Max, Normal, Speed), combining phase-aware Gemini model routing with
 * automatic Adaptive sub-turn thinking.
 */
public enum AgentProfile {
    MAX("MAX", "Max", "Carbon → Hydrogen", Constants.MODEL_CARBON, Constants.MODEL_HYDROGEN,
            "Maximum architectural reasoning (Gemini 4 Carbon) for exploration and planning, paired with Gemini 4 Hydrogen for fast, cache-retained code execution and review. Best for greenfield builds, complex debugging, and repository-wide refactors."),
    NORMAL("NORMAL", "Normal", "Argon → Hydrogen", Constants.MODEL_ARGON, Constants.MODEL_HYDROGEN,
            "Flagship frontier reasoning (Gemini 4 Argon) for exploration and planning, paired with Gemini 4 Hydrogen for high-speed code execution and review. Ideal for daily feature development and bug fixes."),
    SPEED("SPEED", "Speed", "Pure Hydrogen", Constants.MODEL_HYDROGEN, Constants.MODEL_HYDROGEN,
            "End-to-end Gemini 4 Hydrogen across all phases with 100% prefix cache retention from plan to completion. Ideal for unit tests, UI tweaks, and isolated changes.");

    private static final class Constants {
        static final String MODEL_CARBON = "gemini-4-carbon";
        static final String MODEL_ARGON = "gemini-4-argon";
        static final String MODEL_HYDROGEN = "gemini-4-hydrogen";
    }

    public static final AgentProfile DEFAULT = NORMAL;

    private final String id;
    private final String displayName;
    private final String badgeSummary;
    private final String planModel;
    private final String codeModel;
    private final String description;

    AgentProfile(String id, String displayName, String badgeSummary, String planModel, String codeModel,
            String description) {
        this.id = id;
        this.displayName = displayName;
        this.badgeSummary = badgeSummary;
        this.planModel = planModel;
        this.codeModel = codeModel;
        this.description = description;
    }

    /**
     * Returns the stable profile identifier (e.g., {@code "MAX"}, {@code "NORMAL"}, {@code "SPEED"}).
     *
     * @return the profile ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the user-facing display name (e.g., {@code "Max"}, {@code "Normal"}, {@code "Speed"}).
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the pipeline badge summary (e.g., {@code "Argon → Hydrogen"}).
     *
     * @return the badge summary
     */
    public String getBadgeSummary() {
        return badgeSummary;
    }

    /**
     * Returns the Gemini model API name used during {@link WorkflowPhase#EXPLORE} and {@link WorkflowPhase#PLAN}.
     *
     * @return the exploration/planning model API name
     */
    public String getPlanModel() {
        return planModel;
    }

    /**
     * Returns the Gemini model API name used during {@link WorkflowPhase#CODE} and {@link WorkflowPhase#REVIEW}.
     *
     * @return the coding/review model API name
     */
    public String getCodeModel() {
        return codeModel;
    }

    /**
     * Returns the detailed user-facing description of the profile.
     *
     * @return the profile description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Formats the header pill text for this profile (e.g., {@code "Normal • Argon → Hydrogen"}).
     *
     * @return the formatted header label
     */
    public String formatHeaderLabel() {
        return displayName + " • " + badgeSummary;
    }

    /**
     * Resolves the Gemini model API name for the given workflow phase. {@link WorkflowPhase#CODE} and
     * {@link WorkflowPhase#REVIEW} route to {@link #getCodeModel()}, while {@link WorkflowPhase#EXPLORE},
     * {@link WorkflowPhase#PLAN}, and {@code null} route to {@link #getPlanModel()}.
     *
     * @param phase
     *            the workflow phase (may be null)
     * @return the resolved Gemini model API name
     */
    public String resolveModelForPhase(@Nullable WorkflowPhase phase) {
        if (phase == WorkflowPhase.CODE || phase == WorkflowPhase.REVIEW) {
            return codeModel;
        }
        return planModel;
    }

    /**
     * Parses a raw profile ID, display name, or legacy Gemini model name into an {@link AgentProfile}, falling back to
     * {@link #DEFAULT} ({@link #NORMAL}) when blank or unrecognized.
     *
     * @param raw
     *            the raw profile string or legacy model name
     * @return the resolved {@link AgentProfile}
     */
    public static AgentProfile fromString(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return DEFAULT;
        }
        String trimmed = raw.trim();
        for (AgentProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(trimmed) || profile.displayName.equalsIgnoreCase(trimmed)
                    || profile.badgeSummary.equalsIgnoreCase(trimmed)) {
                return profile;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("carbon")) {
            return MAX;
        }
        if (lower.contains("argon") || lower.contains("pro")) {
            return NORMAL;
        }
        if (lower.contains("hydrogen") || lower.contains("flash")) {
            return SPEED;
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
