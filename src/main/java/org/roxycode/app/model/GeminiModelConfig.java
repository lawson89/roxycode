package org.roxycode.app.model;

public record GeminiModelConfig(
    String apiName,
    String name,
    String description
) {
    @Override
    public String toString() {
        return name;
    }
}