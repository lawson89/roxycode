package org.roxycode.app.events;

/** Event published when a topic or implementation plan archive file has been written to disk. */
public record ArchiveUpdatedEvent(String type, String filename) {
    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_PLAN = "plan";
}
