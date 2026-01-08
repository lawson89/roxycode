package org.roxycode.core;

/**
 * Represents the modes of operation for RoxyCode.
 */
public enum RoxyMode {
    /**
     * RoxyCode can answer questions about the current project.
     */
    ASK,

    /**
     * RoxyCode can ask clarifying questions to develop a plan for a feature
     * and store that plan in the plans folder. No changes other than to
     * the plans folder are allowed.
     */
    PLAN,


    /**
     * RoxyCode uses all tools at her disposal to develop the feature.
     * Code changes can only be made in this mode.
     */
    CODE
}
