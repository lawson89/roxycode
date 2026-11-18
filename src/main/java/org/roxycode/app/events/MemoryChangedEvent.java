package org.roxycode.app.events;

/** Event published when global or project agent memory or notes are modified. */
public record MemoryChangedEvent(boolean globalScope) {
}