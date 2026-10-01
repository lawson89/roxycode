package org.roxycode.app.model.config;

import java.util.List;

public record GeminiModels(
    List<GeminiModelConfig> models
) {}