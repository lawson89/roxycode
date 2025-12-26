package org.roxycode.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

@Singleton
public class SettingsService {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);
    private static final String KEY_GEMINI_API_KEY = "geminiApiKey";
    private static final String KEY_RECENT_PROJECTS = "recentProjects";

    private final Preferences preferences;
    private final ObjectMapper objectMapper;

    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.preferences = Preferences.userNodeForPackage(SettingsService.class);
    }

    public String getGeminiApiKey() {
        return preferences.get(KEY_GEMINI_API_KEY, null);
    }

    public void setGeminiApiKey(String key) {
        if (key == null) {
            preferences.remove(KEY_GEMINI_API_KEY);
        } else {
            preferences.put(KEY_GEMINI_API_KEY, key);
        }
    }

    public List<String> getRecentProjects() {
        String json = preferences.get(KEY_RECENT_PROJECTS, "[]");
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            LOG.error("Failed to deserialize recent projects", e);
            return new ArrayList<>();
        }
    }

    public void addRecentProject(String path) {
        List<String> current = getRecentProjects();
        // Avoid duplicates and move to top
        current.remove(path);
        current.addFirst(path);

        try {
            String json = objectMapper.writeValueAsString(current);
            preferences.put(KEY_RECENT_PROJECTS, json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize recent projects", e);
        }
    }
}