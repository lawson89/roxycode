package org.roxycode.app.model;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.springframework.lang.Nullable;

/** 3-Profile Agent Profile system (Max, Normal, Speed) combining phase-aware model routing and adaptive thinking. */
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

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBadgeSummary() {
        return badgeSummary;
    }

    public String getPlanModel() {
        return planModel;
    }

    public String getCodeModel() {
        return codeModel;
    }

    public String getDescription() {
        return description;
    }

    public String formatHeaderLabel() {
        return displayName + " • " + badgeSummary;
    }

    public String resolveModelForPhase(@Nullable WorkflowPhase phase) {
        if (phase == WorkflowPhase.CODE || phase == WorkflowPhase.REVIEW) {
            return codeModel;
        }
        return planModel;
    }

    /** Parses a profile ID, display name, or legacy model name, defaulting to {@link #DEFAULT}. */
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