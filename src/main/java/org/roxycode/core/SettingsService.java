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
import java.nio.file.Path;
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
    private static final String ENV_ROXY_HOME = "ROXY_HOME";

    private final Preferences preferences;
    private final ObjectMapper objectMapper;
    private Dotenv dotenv;

    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.preferences = Preferences.userNodeForPackage(SettingsService.class);

        // Load .env logic
        String envDir = ".";
        if (!Files.exists(Paths.get(".env")) && Files.exists(Paths.get("../.env"))) {
            envDir = "..";
        }
        try {
            this.dotenv = Dotenv.configure().directory(envDir).ignoreIfMissing().load();
        } catch (Exception e) {
            LOG.warn("⚠️ Failed to load .env file: {}", e.getMessage());
        }
    }

    /**
     * Resolves the Roxy Home directory.
     * Default: ./roxy_home
     * Override: ROXY_HOME environment variable
     */
    public Path getRoxyHome() {
        // 1. Check Env Var
        String envHome = System.getenv(ENV_ROXY_HOME);
        if (envHome != null && !envHome.isBlank()) {
            return Paths.get(envHome).toAbsolutePath().normalize();
        }

        // 2. Check .env
        if (dotenv != null) {
            String dotEnvHome = dotenv.get(ENV_ROXY_HOME);
            if (dotEnvHome != null && !dotEnvHome.isBlank()) {
                return Paths.get(dotEnvHome).toAbsolutePath().normalize();
            }
        }

        // 3. Default to current working directory / roxy_home
        return Paths.get("roxy_home").toAbsolutePath().normalize();
    }

    public String getGeminiApiKey() {
        if (dotenv != null) {
            String envKey = dotenv.get(ENV_GEMINI_API_KEY);
            if (envKey != null && !envKey.isBlank()) return envKey;
        }
        String sysEnvKey = System.getenv(ENV_GEMINI_API_KEY);
        if (sysEnvKey != null && !sysEnvKey.isBlank()) return sysEnvKey;
        return preferences.get(KEY_GEMINI_API_KEY, null);
    }

    public void setGeminiApiKey(String key) {
        if (key == null) preferences.remove(KEY_GEMINI_API_KEY);
        else preferences.put(KEY_GEMINI_API_KEY, key);
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