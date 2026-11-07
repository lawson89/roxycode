package org.roxycode.app.model;

/**
 * Immutable chat conversation message entry used for transcript export and Q&A topic archiving.
 */
public record ChatTranscriptEntry(String role, String content, String timestamp) {
    public static final String ROLE_AI = "ai";
    public static final String ROLE_USER = "user";
    public static final String ROLE_SYSTEM = "system";
}
