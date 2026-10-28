package org.roxycode.app.events;

import org.roxycode.app.ai.WorkflowPhase;

/**
 * Event published when a workflow phase transition is executed autonomously or requested/cleared for user approval.
 */
public record WorkflowTransitionEvent(WorkflowPhase from, WorkflowPhase to, String reason, boolean autonomous) {
}
