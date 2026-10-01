package org.roxycode.app.ai.services.specs;

import org.roxycode.app.ai.AgentDoc;
import java.util.List;

@AgentDoc("Immutable structure for implementation tasks.")
public record TechnicalSpec(
    String architectureGoal,
    List<String> constraints,
    List<String> implementationSteps
) {
    public TechnicalSpec {
        constraints = List.copyOf(constraints);
        implementationSteps = List.copyOf(implementationSteps);
    }
}
