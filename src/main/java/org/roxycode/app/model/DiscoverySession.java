package org.roxycode.app.model;

import java.util.List;
import org.roxycode.app.ai.AgentDoc;

@AgentDoc("Tracks the requirements gathering progress for a feature.")
public record DiscoverySession(
    String featureName, List<String> userNeeds, List<String> clarifications, boolean complete) {
  public DiscoverySession {
    userNeeds = List.copyOf(userNeeds);
    clarifications = List.copyOf(clarifications);
  }
}
