package org.roxycode.app.model;

import java.util.List;

/**
 * Wrapper record for a list of Gemini model configurations.
 *
 * @param models
 *            the list of Gemini model configurations
 */
public record GeminiModels(List<GeminiModelConfig> models) {
}
