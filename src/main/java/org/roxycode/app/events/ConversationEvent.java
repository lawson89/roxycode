package org.roxycode.app.events;

/** Common interface for events scoped to a specific chat conversation. */
@FunctionalInterface
public interface ConversationEvent {
    String conversationId();
}
