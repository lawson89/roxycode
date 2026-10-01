package org.roxycode.app.ai;

public record JexlExecutionEvent(String script, Object result, boolean success, String error) {}
