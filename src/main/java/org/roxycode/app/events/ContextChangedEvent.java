package org.roxycode.app.events;

/**
 * Event published when global or project context/reference folders are updated.
 *
 * @param globalScope
 *            true if global context changed, false if project context changed
 */
public record ContextChangedEvent(boolean globalScope) {
}
