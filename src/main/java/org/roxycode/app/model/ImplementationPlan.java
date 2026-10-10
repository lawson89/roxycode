package org.roxycode.app.model;

import org.roxycode.app.ai.AgentDoc;
import java.util.List;

@AgentDoc("Immutable structure for the project implementation plan.")
public record ImplementationPlan(
    String title,
    String goal,
    List<String> requirements,
    List<TechStep> technicalSteps,
    boolean userApproved,
    String approvalTimestamp,
    String approvedBy,
    String completedOn
) {
    public record TechStep(String description, boolean completed) {}

    public ImplementationPlan {
        requirements = requirements != null ? List.copyOf(requirements) : List.of();
        technicalSteps = technicalSteps != null ? List.copyOf(technicalSteps) : List.of();
    }
}
