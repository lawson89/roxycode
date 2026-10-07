package org.roxycode.app.model;

import org.roxycode.app.ai.AgentDoc;
import java.util.List;

@AgentDoc("Immutable structure for business requirements.")
public record FunctionalSpec(
    String title,
    String goal,
    List<String> requirements
) {
    public FunctionalSpec {
        requirements = List.copyOf(requirements);
    }
}