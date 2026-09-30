package org.roxycode.app.model;

public record SystemTool(
    String name,
    String version,
    String status,
    String description
) {}