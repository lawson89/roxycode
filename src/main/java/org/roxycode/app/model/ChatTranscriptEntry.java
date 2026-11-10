package org.roxycode.app.model;

/**
 * Immutable chat conversation message entry used for transcript export and Q&A topic archiving.
 *
 * @param role
 *            the role of the message sender (e.g., user, ai, system)
 * @param content
 *            the content of the message
 * @param timestamp
 *            the timestamp of the message
 */
public record ChatTranscriptEntry(String role, String content, String timestamp) {
    public static final String ROLE_AI = "ai";
    public static final String ROLE_USER = "user";
    public static final String ROLE_SYSTEM = "system";
}
