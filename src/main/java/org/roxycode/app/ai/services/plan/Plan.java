package org.roxycode.app.ai.services.plan;

import org.roxycode.app.ai.AgentDoc;
import java.util.List;

/**
 * Represents a project plan and its detailed specification.
 */
@AgentDoc("Represents a project plan and its detailed specification.")
public record Plan(
    @AgentDoc("The name of the plan.")
    String name,
    @AgentDoc("The user who created the plan.")
    String user,
    @AgentDoc("The original user request.")
    String userRequest,
    @AgentDoc("The core goal of the plan.")
    String goal,
    @AgentDoc("Whether the user has approved the plan.")
    boolean userApproved,
    @AgentDoc("The timestamp when the plan was approved.")
    String approvalTimestamp,
    @AgentDoc("The timestamp when the plan was completed.")
    String completedOn,
    @AgentDoc("The list of functional requirements.")
    List<String> functionalRequirements,
    @AgentDoc("The list of technical constraints.")
    List<String> technicalConstraints,
    @AgentDoc("Notes from global review.")
    List<String> globalReviewNotes,
    @AgentDoc("The list of implementation tasks.")
    List<TaskItem> tasks,
    @AgentDoc("The current status of the plan.")
    PlanStatus status
) {

    /**
     * Returns the status of the plan as a string.
     * @return the status string
     */
    public String getStatusString() {
        return status.name();
    }

    /**
     * Represents a single task item within a plan.
     */
    @AgentDoc("A granular task item in a plan.")
    public record TaskItem(
        @AgentDoc("The description of the task.")
        String text,
        @AgentDoc("The current status of the task.")
        TaskStatus status
    ) {
        @Override
        public String toString() {
            return "[" + status.name() + "] " + text;
        }
    }

    /**
     * Enumeration of task statuses.
     */
    @AgentDoc("The status of a task item.")
    public enum TaskStatus {
        @AgentDoc("Task is pending.")
        PENDING,
        @AgentDoc("Task is in progress.")
        IN_PROGRESS,
        @AgentDoc("Task is completed.")
        COMPLETED,
        @AgentDoc("Task has failed.")
        FAILED
    }
}