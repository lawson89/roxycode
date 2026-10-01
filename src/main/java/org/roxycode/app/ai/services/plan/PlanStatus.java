package org.roxycode.app.ai.services.plan;

import org.roxycode.app.ai.AgentDoc;

/**
 * Enumeration of plan statuses.
 * These correspond to the directories where plans are stored.
 */
@AgentDoc("The status of a plan.")
public enum PlanStatus {
    /**
     * The plan is in the design phase.
     */
    @AgentDoc("Plan is being designed.")
    PLAN,
    /**
     * The plan is being implemented.
     */
    @AgentDoc("Plan is being implemented.")
    CODE,
    /**
     * The plan is in review phase for QA Automation Engineer verification.
     */
    @AgentDoc("Plan is being reviewed.")
    REVIEW,
    /**
     * The plan has been successfully implemented.
     */
    @AgentDoc("Plan implementation is complete.")
    COMPLETE;

    /**
     * Returns the directory name associated with this status.
     * @return the directory name (lowercase version of the status name)
     */
    public String getDirName() {
        return name().toLowerCase();
    }

    /**
     * Parses a string into a PlanStatus.
     * @param value the string value
     * @return the corresponding PlanStatus
     * @throws IllegalArgumentException if the value is unknown
     */
    public static PlanStatus fromString(String value) {
        if (value == null) {
            return null;
        }
        return valueOf(value.toUpperCase());
    }
}