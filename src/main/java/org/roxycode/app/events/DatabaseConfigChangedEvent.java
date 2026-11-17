package org.roxycode.app.events;

/**
 * Published when global database connections or per-project database grants change.
 */
public record DatabaseConfigChangedEvent(boolean globalScope) {
}
