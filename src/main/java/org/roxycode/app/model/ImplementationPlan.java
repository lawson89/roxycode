package org.roxycode.app.model;

import java.util.ArrayList;
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

    public ImplementationPlan withTechnicalSteps(List<TechStep> newSteps) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                newSteps,
                userApproved,
                approvalTimestamp,
                approvedBy,
                completedOn,
                handoffLog);
    }

    public ImplementationPlan withApproval(String user, String timestamp) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                technicalSteps,
                true,
                timestamp,
                user,
                completedOn,
                handoffLog);
    }

    public ImplementationPlan withCompletedOn(String timestamp) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                technicalSteps,
                userApproved,
                approvalTimestamp,
                approvedBy,
                timestamp,
                handoffLog);
    }

    public ImplementationPlan withHandoffLog(List<LogEntry> newLog) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                technicalSteps,
                userApproved,
                approvalTimestamp,
                approvedBy,
                completedOn,
                newLog);
    }

    public ImplementationPlan withAddedHandoffLogEntry(
            String timestamp, String user, String message) {
        List<LogEntry> updatedLog = new ArrayList<>(handoffLog);
        updatedLog.add(new LogEntry(timestamp, user, message));
        return withHandoffLog(updatedLog);
    }
}
