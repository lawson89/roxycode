package org.roxycode.app.events;

/**
 * Event published when a file is modified within the project sandbox.
 */
public record FileChangedEvent(String path) {}
