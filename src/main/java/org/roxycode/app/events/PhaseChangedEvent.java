package org.roxycode.app.events;

import org.roxycode.app.ai.WorkflowPhase;

/** Event published when the active workflow phase changes. */
public record PhaseChangedEvent(WorkflowPhase phase) {
}
