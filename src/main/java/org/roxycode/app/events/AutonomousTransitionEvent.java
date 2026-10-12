package org.roxycode.app.events;

import org.roxycode.app.ai.WorkflowPhase;

/**
 * Event triggered when an autonomous phase transition occurs.
 */
public record AutonomousTransitionEvent(WorkflowPhase from, WorkflowPhase to, String reason) {}
