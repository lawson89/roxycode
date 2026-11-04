package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppSettings {

    public static final String DEFAULT_THEME = "FlatLaf Light";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";
    public static final String DEFAULT_THINKING_LEVEL = ThinkingLevel.DEFAULT.getId();
    public static final int DEFAULT_MAX_AGENT_TOOL_TURNS = 50;
    public static final int DEFAULT_WINDOW_WIDTH = 1200;
    public static final int DEFAULT_WINDOW_HEIGHT = 900;

    private String theme = DEFAULT_THEME;
    private String geminiApiKey = "";
    private String currentProjectPath = "";
    private String geminiModel = DEFAULT_GEMINI_MODEL;
    private String thinkingLevel = DEFAULT_THINKING_LEVEL;
    private int maxAgentToolTurns = DEFAULT_MAX_AGENT_TOOL_TURNS;
    private int maxChatMemoryMessages = 50;
    private boolean logToolCalls = false;
    private boolean googleGroundingEnabled = false;
    private List<String> globalContextFolders = new ArrayList<>();
    private Integer windowX;
    private Integer windowY;
    private int windowWidth = DEFAULT_WINDOW_WIDTH;
    private int windowHeight = DEFAULT_WINDOW_HEIGHT;
    private boolean windowMaximized = false;

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
            this.googleGroundingEnabled = other.googleGroundingEnabled;
            this.globalContextFolders = new ArrayList<>(
                    Objects.requireNonNullElse(other.globalContextFolders, List.of()));
            this.windowX = other.windowX;
            this.windowY = other.windowY;
            this.windowWidth = other.windowWidth;
            this.windowHeight = other.windowHeight;
            this.windowMaximized = other.windowMaximized;
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

    public boolean isGoogleGroundingEnabled() {
        return googleGroundingEnabled;
    }

    public void setGoogleGroundingEnabled(boolean googleGroundingEnabled) {
        this.googleGroundingEnabled = googleGroundingEnabled;
    }

    public List<String> getGlobalContextFolders() {
        return List.copyOf(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }

    public void setGlobalContextFolders(List<String> globalContextFolders) {
        this.globalContextFolders = new ArrayList<>(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }

    public Integer getWindowX() {
        return windowX;
    }

    public void setWindowX(Integer windowX) {
        this.windowX = windowX;
    }

    public Integer getWindowY() {
        return windowY;
    }

    public void setWindowY(Integer windowY) {
        this.windowY = windowY;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public boolean isWindowMaximized() {
        return windowMaximized;
    }

    public void setWindowMaximized(boolean windowMaximized) {
        this.windowMaximized = windowMaximized;
    }
}
