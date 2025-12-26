package org.roxycode.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

@Singleton
public class SettingsService {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);
    private static final String KEY_GEMINI_API_KEY = "geminiApiKey";
    private static final String KEY_RECENT_PROJECTS = "recentProjects";
    private static final String ENV_GEMINI_API_KEY = "GEMINI_API_KEY";

    private final Preferences preferences;
    private final ObjectMapper objectMapper;
    private Dotenv dotenv;

    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.preferences = Preferences.userNodeForPackage(SettingsService.class);

        // Smart .env loading: Check current dir, then check parent dir (for Maven/Target issues)
        String envDir = ".";
        if (!Files.exists(Paths.get(".env")) && Files.exists(Paths.get("../.env"))) {
            envDir = "..";
            LOG.info("📄 Found .env in parent directory. Adjusting search path.");
        }

        try {
            this.dotenv = Dotenv.configure()
                    .directory(envDir)
                    .ignoreIfMissing()
                    .load();
        } catch (Exception e) {
            LOG.warn("⚠️ Failed to load .env file: {}", e.getMessage());
        }
    }

    public String getGeminiApiKey() {
        // Priority 1: Check .env file
        if (dotenv != null) {
            String envKey = dotenv.get(ENV_GEMINI_API_KEY);
            if (envKey != null && !envKey.isBlank()) {
                return envKey;
            }
        }

        // Priority 2: Check System Environment Variables (Docker/CI friendly)
        String sysEnvKey = System.getenv(ENV_GEMINI_API_KEY);
        if (sysEnvKey != null && !sysEnvKey.isBlank()) {
            return sysEnvKey;
        }

        // Priority 3: Check Java Preferences
        return preferences.get(KEY_GEMINI_API_KEY, null);
    }

    public void setGeminiApiKey(String key) {
        // We only write to Preferences, never to .env programmatically for security
        if (key == null) {
            preferences.remove(KEY_GEMINI_API_KEY);
        } else {
            preferences.put(KEY_GEMINI_API_KEY, key);
        }
    }

    public List<String> getRecentProjects() {
        String json = preferences.get(KEY_RECENT_PROJECTS, "[]");
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            LOG.error("Failed to deserialize recent projects", e);
            return new ArrayList<>();
        }
    }

    public void addRecentProject(String path) {
        List<String> current = getRecentProjects();
        current.remove(path);
        current.add(0, path);

        try {
            String json = objectMapper.writeValueAsString(current);
            preferences.put(KEY_RECENT_PROJECTS, json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize recent projects", e);
        }
    }
}