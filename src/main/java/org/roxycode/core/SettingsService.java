package org.roxycode.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

@Singleton
public class SettingsService {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    // Keys
    private static final String KEY_GEMINI_API_KEY = "geminiApiKey";
    private static final String KEY_RECENT_PROJECTS = "recentProjects";
    private static final String KEY_MAX_TURNS = "maxTurns";
    private static final String KEY_THEME = "theme";
    private static final String KEY_GEMINI_MODEL = "geminiModel";
    private static final String KEY_CACHE_ENABLED = "cacheEnabled";
    private static final String KEY_CACHE_TTL = "cacheTTL";
    private static final String KEY_CACHE_MIN_SIZE = "cacheMinSize";
    private static final String KEY_MAX_TURNS_PER_MINUTE = "maxTurnsPerMinute";

    // Sliding Window Keys
    private static final String KEY_HISTORY_WINDOW_SIZE = "historyWindowSize";
    private static final String KEY_LOG_LINES_COUNT = "logLinesCount";

    // Defaults
    private static final int DEFAULT_MAX_TURNS = 15;
    private static final String DEFAULT_THEME = "Light";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
    private static final int DEFAULT_HISTORY_WINDOW_SIZE = 30; // Number of messages to keep
    private static final int DEFAULT_LOG_LINES_COUNT = 100;
    private static final boolean DEFAULT_CACHE_ENABLED = true;
    private static final int DEFAULT_CACHE_TTL = 30;
    private static final int DEFAULT_CACHE_MIN_SIZE = 32768;
    private static final int DEFAULT_MAX_TURNS_PER_MINUTE = 30;

    private static final String KEY_CURRENT_PROJECT = "currentProject";

    private final Preferences preferences;
    private final ObjectMapper objectMapper;

    public SettingsService(@Named("json") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.preferences = Preferences.userNodeForPackage(SettingsService.class);
    }

    public Path getRoxyHome() {
        return Paths.get("roxy_home").toAbsolutePath().normalize();
    }

    public String getGeminiApiKey() {
        return preferences.get(KEY_GEMINI_API_KEY, null);
    }

    public void setGeminiApiKey(String key) {
        if (key == null) preferences.remove(KEY_GEMINI_API_KEY);
        else preferences.put(KEY_GEMINI_API_KEY, key);
    }

    public String getCurrentProject() {
        return preferences.get(KEY_CURRENT_PROJECT, null);
    }

    public Path getCurrentProjectPath() {
        String project = getCurrentProject();
        if (project == null) return null;
        return Path.of(project);
    }

    public void setCurrentProject(String project) {
        if (project == null) preferences.remove(KEY_CURRENT_PROJECT);
        else preferences.put(KEY_CURRENT_PROJECT, project);
    }

    public List<String> getRecentProjects() {
        String json = preferences.get(KEY_RECENT_PROJECTS, "[]");
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            LOG.error("Failed to deserialize recent projects", e);
            return new ArrayList<>();
        }
    }

    public void addRecentProject(String path) {
        List<String> current = getRecentProjects();
        current.remove(path);
        current.addFirst(path);
        try {
            String json = objectMapper.writeValueAsString(current);
            preferences.put(KEY_RECENT_PROJECTS, json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize recent projects", e);
        }
    }

    public int getMaxTurns() {
        return preferences.getInt(KEY_MAX_TURNS, DEFAULT_MAX_TURNS);
    }

    public void setMaxTurns(int turns) {
        preferences.putInt(KEY_MAX_TURNS, turns);
    }

    public String getTheme() {
        return preferences.get(KEY_THEME, DEFAULT_THEME);
    }

    public void setTheme(String theme) {
        preferences.put(KEY_THEME, theme);
    }

    public String getGeminiModel() {
        return preferences.get(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL);
    }

    public void setGeminiModel(String model) {
        preferences.put(KEY_GEMINI_MODEL, model);
    }

    public int getHistoryWindowSize() {
        return preferences.getInt(KEY_HISTORY_WINDOW_SIZE, DEFAULT_HISTORY_WINDOW_SIZE);
    }

    public void setHistoryWindowSize(int size) {
        preferences.putInt(KEY_HISTORY_WINDOW_SIZE, size);
    }

    public int getLogLinesCount() {
        return preferences.getInt(KEY_LOG_LINES_COUNT, DEFAULT_LOG_LINES_COUNT);
    }

    public void setLogLinesCount(int count) {
        preferences.putInt(KEY_LOG_LINES_COUNT, count);
    }

    public boolean isCacheEnabled() {
        return preferences.getBoolean(KEY_CACHE_ENABLED, DEFAULT_CACHE_ENABLED);
    }

    public void setCacheEnabled(boolean enabled) {
        preferences.putBoolean(KEY_CACHE_ENABLED, enabled);
    }

    public int getCacheTTL() {
        return preferences.getInt(KEY_CACHE_TTL, DEFAULT_CACHE_TTL);
    }

    public void setCacheTTL(int ttl) {
        preferences.putInt(KEY_CACHE_TTL, ttl);
    }

    public int getCacheMinSize() {
        return preferences.getInt(KEY_CACHE_MIN_SIZE, DEFAULT_CACHE_MIN_SIZE);
    }

    public void setCacheMinSize(int minSize) {
        preferences.putInt(KEY_CACHE_MIN_SIZE, minSize);
    }
    public int getMaxTurnsPerMinute() {
        return preferences.getInt(KEY_MAX_TURNS_PER_MINUTE, DEFAULT_MAX_TURNS_PER_MINUTE);
    }

    public void setMaxTurnsPerMinute(int maxTurnsPerMinute) {
        preferences.putInt(KEY_MAX_TURNS_PER_MINUTE, maxTurnsPerMinute);
    }
}
