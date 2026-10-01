package org.roxycode.app.model.config;

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