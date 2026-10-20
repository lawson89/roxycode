package org.roxycode.app.events;

import org.roxycode.app.model.ImplementationPlan;

/** Event published when the current implementation plan is updated or cleared. */
public record PlanUpdatedEvent(ImplementationPlan plan) {}
