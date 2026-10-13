package org.roxycode.app.events;

public record ToolExecutionEvent(
    String conversationId, String script, Object result, boolean success, String error) {}
