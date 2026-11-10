package org.roxycode.app.events;

/**
 * Event published when global or project agent memory or notes are modified.
 *
 * @param globalScope
 *            true if global memory changed, false if project memory changed
 */
public record MemoryChangedEvent(boolean globalScope) {
}
