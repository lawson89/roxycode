package org.roxycode;

import jakarta.inject.Singleton;
import java.util.prefs.Preferences;

@Singleton
public class ConfigService {
    private static final String MODEL_NAME_KEY = "gemini.model.name";
    private static final String DEFAULT_MODEL = "gemini-3-flash-preview";
    private final Preferences prefs = Preferences.userNodeForPackage(ConfigService.class);

    public String getModelName() {
        return prefs.get(MODEL_NAME_KEY, DEFAULT_MODEL);
    }

    public void setModelName(String modelName) {
        prefs.put(MODEL_NAME_KEY, modelName);
    }
}
