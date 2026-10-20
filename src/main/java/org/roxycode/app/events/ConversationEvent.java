package org.roxycode.app.events;

/** Common interface for events scoped to a specific chat conversation. */
public interface ConversationEvent {
    String conversationId();
}
