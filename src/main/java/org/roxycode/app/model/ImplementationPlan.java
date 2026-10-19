package org.roxycode.app.model;

import java.util.List;
import org.roxycode.app.ai.AgentDoc;

@AgentDoc("Immutable structure for the project implementation plan.")
public record ImplementationPlan(
        String title,
        String goal,
        List<String> requirements,
        List<TechStep> technicalSteps,
        boolean userApproved,
        String approvalTimestamp,
        String approvedBy,
        String completedOn,
        List<LogEntry> handoffLog) {
    public record TechStep(String description, boolean completed) {}

    @AgentDoc("A structured log entry for the implementation history.")
    public record LogEntry(String timestamp, String user, String message) {}

    public ImplementationPlan {
        requirements = requirements != null ? List.copyOf(requirements) : List.of();
        technicalSteps = technicalSteps != null ? List.copyOf(technicalSteps) : List.of();
        handoffLog = handoffLog != null ? List.copyOf(handoffLog) : List.of();
    }
}
