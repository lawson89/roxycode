package org.roxycode.app.ai.services.discovery;

import org.roxycode.app.ai.AgentDoc;
import java.util.List;
import java.util.ArrayList;

@AgentDoc("Tracks the requirements gathering progress for a feature.")
public record DiscoverySession(
    String featureName,
    List<String> userNeeds,
    List<String> clarifications,
    boolean complete
) {
    public DiscoverySession {
        userNeeds = List.copyOf(userNeeds);
        clarifications = List.copyOf(clarifications);
    }
}
