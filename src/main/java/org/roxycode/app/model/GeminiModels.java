package org.roxycode.app.model;

import java.util.List;

public record GeminiModels(
    List<GeminiModelConfig> models
) {}