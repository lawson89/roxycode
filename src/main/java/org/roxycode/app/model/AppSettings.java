package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    public static final String DEFAULT_THEME = "FlatLaf Light";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";
    public static final String DEFAULT_THINKING_LEVEL = ThinkingLevel.DEFAULT.getId();
    public static final int DEFAULT_MAX_AGENT_TOOL_TURNS = 50;

    private String theme = DEFAULT_THEME;
    private String geminiApiKey = "";
    private String currentProjectPath = "";
    private String geminiModel = DEFAULT_GEMINI_MODEL;
    private String thinkingLevel = DEFAULT_THINKING_LEVEL;
    private int maxAgentToolTurns = DEFAULT_MAX_AGENT_TOOL_TURNS;
    private int maxChatMemoryMessages = 50;
    private boolean logToolCalls = false;
    private List<String> globalContextFolders = new ArrayList<>();

    public AppSettings() {
    }

    public AppSettings(AppSettings other) {
        if (other != null) {
            this.theme = other.theme;
            this.geminiApiKey = other.geminiApiKey;
            this.currentProjectPath = other.currentProjectPath;
            this.geminiModel = other.geminiModel;
            this.thinkingLevel = other.thinkingLevel;
            this.maxAgentToolTurns = other.maxAgentToolTurns;
            this.maxChatMemoryMessages = other.maxChatMemoryMessages;
            this.logToolCalls = other.logToolCalls;
            this.globalContextFolders = new ArrayList<>(
                    Objects.requireNonNullElse(other.globalContextFolders, List.of()));
        }
    }

    public AppSettings copy() {
        return new AppSettings(this);
    }

    public String getTheme() {
        return StringUtils.defaultIfBlank(theme, DEFAULT_THEME);
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    public void setGeminiApiKey(String geminiApiKey) {
        this.geminiApiKey = geminiApiKey;
    }

    public String getCurrentProjectPath() {
        return currentProjectPath;
    }

    public void setCurrentProjectPath(String currentProjectPath) {
        this.currentProjectPath = currentProjectPath;
    }

    public String getGeminiModel() {
        return StringUtils.defaultIfBlank(geminiModel, DEFAULT_GEMINI_MODEL);
    }

    public void setGeminiModel(String geminiModel) {
        this.geminiModel = geminiModel;
    }

    public String getThinkingLevel() {
        return ThinkingLevel.fromString(thinkingLevel).getId();
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = ThinkingLevel.fromString(thinkingLevel).getId();
    }

    public int getMaxAgentToolTurns() {
        return maxAgentToolTurns;
    }

    public void setMaxAgentToolTurns(int maxAgentToolTurns) {
        this.maxAgentToolTurns = maxAgentToolTurns;
    }

    public int getMaxChatMemoryMessages() {
        return maxChatMemoryMessages;
    }

    public void setMaxChatMemoryMessages(int maxChatMemoryMessages) {
        this.maxChatMemoryMessages = maxChatMemoryMessages;
    }

    public boolean isLogToolCalls() {
        return logToolCalls;
    }

    public void setLogToolCalls(boolean logToolCalls) {
        this.logToolCalls = logToolCalls;
    }

    public List<String> getGlobalContextFolders() {
        return List.copyOf(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }

    public void setGlobalContextFolders(List<String> globalContextFolders) {
        this.globalContextFolders = new ArrayList<>(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }
}
