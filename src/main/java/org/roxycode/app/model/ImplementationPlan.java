package org.roxycode.app.model;

import org.roxycode.app.ai.AgentDoc;
import java.util.List;

@AgentDoc("Immutable structure for the project implementation plan.")
public record ImplementationPlan(
    String title,
    String goal,
    List<String> requirements,
    List<String> technicalSteps
) {
    public ImplementationPlan {
        requirements = List.copyOf(requirements);
        technicalSteps = List.copyOf(technicalSteps);
    }
}