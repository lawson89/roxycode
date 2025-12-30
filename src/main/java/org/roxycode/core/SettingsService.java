package org.roxycode.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String KEY_GEMINI_API_KEY = "geminiApiKey";
    private static final String KEY_RECENT_PROJECTS = "recentProjects";
    private static final String KEY_MAX_TURNS = "maxTurns";
    private static final int MAX_TURNS = 15;
    private static final String KEY_THEME = "theme";
    private static final String DEFAULT_THEME = "Light";
    private static final String KEY_GEMINI_MODEL = "geminiModel";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";

    private static final String KEY_HISTORY_THRESHOLD = "historyThreshold";
    private static final int DEFAULT_HISTORY_THRESHOLD = 50;
    private static final String KEY_COMPACTION_CHUNK_SIZE = "compactionChunkSize";
    private static final int DEFAULT_COMPACTION_CHUNK_SIZE = 15;
    private static final String KEY_MAX_SUMMARY_CHUNKS = "maxSummaryChunks";
    private static final int DEFAULT_MAX_SUMMARY_CHUNKS = 5;

    private final Preferences preferences;
    private final ObjectMapper objectMapper;

    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.preferences = Preferences.userNodeForPackage(SettingsService.class);
    }

    /**
     * Resolves the Roxy Home directory.
     * Default: ./roxy_home
     * Override: ROXY_HOME environment variable
     */
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

    public List<String> getRecentProjects() {
        String json = preferences.get(KEY_RECENT_PROJECTS, "[]");
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
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

    public Path getCurrentProject() {
        return Paths.get(".").toAbsolutePath().normalize();
    }

    public int getMaxTurns() {
        return preferences.getInt(KEY_MAX_TURNS, MAX_TURNS);
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

    public int getHistoryThreshold() {
        return preferences.getInt(KEY_HISTORY_THRESHOLD, DEFAULT_HISTORY_THRESHOLD);
    }

    public void setHistoryThreshold(int threshold) {
        preferences.putInt(KEY_HISTORY_THRESHOLD, threshold);
    }

    public int getCompactionChunkSize() {
        return preferences.getInt(KEY_COMPACTION_CHUNK_SIZE, DEFAULT_COMPACTION_CHUNK_SIZE);
    }

    public void setCompactionChunkSize(int chunkSize) {
        preferences.putInt(KEY_COMPACTION_CHUNK_SIZE, chunkSize);
    }

    public int getMaxSummaryChunks() {
        return preferences.getInt(KEY_MAX_SUMMARY_CHUNKS, DEFAULT_MAX_SUMMARY_CHUNKS);
    }

    public void setMaxSummaryChunks(int maxChunks) {
        preferences.putInt(KEY_MAX_SUMMARY_CHUNKS, maxChunks);
    }
}