package org.roxycode.app.events;

import org.roxycode.app.ai.WorkflowPhase;

/** Event published when a workflow phase transition is requested or cleared. */
public record TransitionRequestedEvent(
        WorkflowPhase current, WorkflowPhase requested, String reason) {}
