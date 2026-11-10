package org.roxycode.app.events;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Event published when the AI agent requests interactive input or confirmation from the user.
 */
public record UserInputRequestEvent(String conversationId, PromptType promptType, String question, String details,
        String defaultValue, List<String> options, CompletableFuture<Object> responseFuture) {

    public enum PromptType {
        CONFIRM, TEXT, SINGLE_SELECT, MULTI_SELECT
    }

    public UserInputRequestEvent {
        if (options == null) {
            options = List.of();
        } else {
            options = List.copyOf(options);
        }
    }
}
