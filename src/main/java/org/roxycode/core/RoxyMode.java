package org.roxycode.core;

/**
 * Represents the modes of operation for RoxyCode.
 */
public enum RoxyMode {
    /**
     * RoxyCode can answer questions about the current project.
     */
    DISCOVERY,

    /**
     * RoxyCode can ask clarifying questions to develop a plan for a feature
     * and store that plan in the plans folder. No changes other than to
     * the plans folder are allowed.
     */
    PLANNING,

    /**
     * RoxyCode uses all tools at her disposal to develop the feature.
     * Code changes can only be made in this mode.
     */
    IMPLEMENTING,

    /**
     * RoxyCode can either close the feature or return to Planning or Implementing
     * based on the user's feedback.
     */
    FEEDBACK
}
