package org.roxycode;

import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;
import java.util.prefs.Preferences;

@Singleton
public class ConfigService {
    private static final String MODEL_NAME_KEY = "gemini.model.name";
    private static final String DEFAULT_MODEL = "gemini-1.5-flash";
    private final Preferences prefs = Preferences.userNodeForPackage(ConfigService.class);

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    public String getModelName() {
        return prefs.get(MODEL_NAME_KEY, DEFAULT_MODEL);
    }

    public void setModelName(String modelName) {
        prefs.put(MODEL_NAME_KEY, modelName);
    }

    public String getApiKey() {
        String effectiveKey = (apiKey == null || apiKey.isBlank()) ? System.getProperty("GEMINI_API_KEY") : apiKey;
        
        if (effectiveKey == null || effectiveKey.isBlank()) {
             throw new IllegalStateException("GEMINI_API_KEY is not set (Property 'GEMINI_API_KEY' missing from .env and System properties)");
        }
        return effectiveKey;
    }
}
