/**
 * Event published when memory changed event.
 */
package org.roxycode.app.events;

public record MemoryChangedEvent(boolean globalScope) {
}
