package org.roxycode.app.events;

public record UserMessageEvent(String conversationId, String user, String message) {
}
