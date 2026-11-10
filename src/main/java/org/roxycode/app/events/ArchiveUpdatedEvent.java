package org.roxycode.app.events;

/**
 * Event published when a topic or implementation plan archive file has been written to disk.
 *
 * @param type
 *            the type of archive ('topic' or 'plan')
 * @param filename
 *            the name of the archived file
 */
public record ArchiveUpdatedEvent(String type, String filename) {
    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_PLAN = "plan";
}