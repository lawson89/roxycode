package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Represents the global application settings for RoxyCode.
 */
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
    private List<String> globalReferenceFolders = new ArrayList<>();
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
            this.globalReferenceFolders = new ArrayList<>(
                    Objects.requireNonNullElse(other.globalReferenceFolders, List.of()));
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

    /**
     * Gets the current theme name.
     *
     * @return the theme name
     */
    public String getTheme() {
        return StringUtils.defaultIfBlank(theme, DEFAULT_THEME);
    }

    /**
     * Sets the theme name.
     *
     * @param theme
     *            the theme name to set
     */
    public void setTheme(String theme) {
        this.theme = theme;
    }

    /**
     * Gets the Gemini API key.
     *
     * @return the Gemini API key
     */
    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    /**
     * Sets the Gemini API key.
     *
     * @param geminiApiKey
     *            the Gemini API key to set
     */
    public void setGeminiApiKey(String geminiApiKey) {
        this.geminiApiKey = geminiApiKey;
    }

    /**
     * Gets the current project path.
     *
     * @return the current project path
     */
    public String getCurrentProjectPath() {
        return currentProjectPath;
    }

    /**
     * Sets the current project path.
     *
     * @param currentProjectPath
     *            the current project path to set
     */
    public void setCurrentProjectPath(String currentProjectPath) {
        this.currentProjectPath = currentProjectPath;
    }

    /**
     * Gets the Gemini model API name.
     *
     * @return the Gemini model API name
     */
    public String getGeminiModel() {
        return StringUtils.defaultIfBlank(geminiModel, DEFAULT_GEMINI_MODEL);
    }

    /**
     * Sets the Gemini model API name.
     *
     * @param geminiModel
     *            the Gemini model API name to set
     */
    public void setGeminiModel(String geminiModel) {
        this.geminiModel = geminiModel;
    }

    /**
     * Gets the thinking level.
     *
     * @return the thinking level ID
     */
    public String getThinkingLevel() {
        return ThinkingLevel.fromString(thinkingLevel).getId();
    }

    /**
     * Sets the thinking level.
     *
     * @param thinkingLevel
     *            the thinking level ID to set
     */
    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = ThinkingLevel.fromString(thinkingLevel).getId();
    }

    /**
     * Gets the maximum number of agent tool turns per request.
     *
     * @return the maximum number of agent tool turns
     */
    public int getMaxAgentToolTurns() {
        return maxAgentToolTurns;
    }

    /**
     * Sets the maximum number of agent tool turns per request.
     *
     * @param maxAgentToolTurns
     *            the maximum number of agent tool turns to set
     */
    public void setMaxAgentToolTurns(int maxAgentToolTurns) {
        this.maxAgentToolTurns = maxAgentToolTurns;
    }

    /**
     * Gets the maximum number of chat memory messages to retain.
     *
     * @return the maximum number of chat memory messages
     */
    public int getMaxChatMemoryMessages() {
        return maxChatMemoryMessages;
    }

    /**
     * Sets the maximum number of chat memory messages to retain.
     *
     * @param maxChatMemoryMessages
     *            the maximum number of chat memory messages to set
     */
    public void setMaxChatMemoryMessages(int maxChatMemoryMessages) {
        this.maxChatMemoryMessages = maxChatMemoryMessages;
    }

    /**
     * Checks if tool calls should be logged.
     *
     * @return true if tool calls should be logged, false otherwise
     */
    public boolean isLogToolCalls() {
        return logToolCalls;
    }

    /**
     * Sets whether tool calls should be logged.
     *
     * @param logToolCalls
     *            true to log tool calls, false otherwise
     */
    public void setLogToolCalls(boolean logToolCalls) {
        this.logToolCalls = logToolCalls;
    }

    /**
     * Checks if Google Search Grounding is enabled.
     *
     * @return true if Google Search Grounding is enabled, false otherwise
     */
    public boolean isGoogleGroundingEnabled() {
        return googleGroundingEnabled;
    }

    /**
     * Sets whether Google Search Grounding is enabled.
     *
     * @param googleGroundingEnabled
     *            true to enable Google Search Grounding, false otherwise
     */
    public void setGoogleGroundingEnabled(boolean googleGroundingEnabled) {
        this.googleGroundingEnabled = googleGroundingEnabled;
    }

    /**
     * Gets the list of global context folders.
     *
     * @return the list of global context folders
     */
    public List<String> getGlobalContextFolders() {
        return List.copyOf(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }

    /**
     * Sets the list of global context folders.
     *
     * @param globalContextFolders
     *            the list of global context folders to set
     */
    public void setGlobalContextFolders(List<String> globalContextFolders) {
        this.globalContextFolders = new ArrayList<>(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }

    /**
     * Gets the list of global reference folders.
     *
     * @return the list of global reference folders
     */
    public List<String> getGlobalReferenceFolders() {
        return List.copyOf(Objects.requireNonNullElse(globalReferenceFolders, List.of()));
    }

    /**
     * Sets the list of global reference folders.
     *
     * @param globalReferenceFolders
     *            the list of global reference folders to set
     */
    public void setGlobalReferenceFolders(List<String> globalReferenceFolders) {
        this.globalReferenceFolders = new ArrayList<>(Objects.requireNonNullElse(globalReferenceFolders, List.of()));
    }

    /**
     * Gets the window X coordinate.
     *
     * @return the window X coordinate
     */
    public Integer getWindowX() {
        return windowX;
    }

    /**
     * Sets the window X coordinate.
     *
     * @param windowX
     *            the window X coordinate to set
     */
    public void setWindowX(Integer windowX) {
        this.windowX = windowX;
    }

    /**
     * Gets the window Y coordinate.
     *
     * @return the window Y coordinate
     */
    public Integer getWindowY() {
        return windowY;
    }

    /**
     * Sets the window Y coordinate.
     *
     * @param windowY
     *            the window Y coordinate to set
     */
    public void setWindowY(Integer windowY) {
        this.windowY = windowY;
    }

    /**
     * Gets the window width.
     *
     * @return the window width
     */
    public int getWindowWidth() {
        return windowWidth;
    }

    /**
     * Sets the window width.
     *
     * @param windowWidth
     *            the window width to set
     */
    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    /**
     * Gets the window height.
     *
     * @return the window height
     */
    public int getWindowHeight() {
        return windowHeight;
    }

    /**
     * Sets the window height.
     *
     * @param windowHeight
     *            the window height to set
     */
    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    /**
     * Checks if the window is maximized.
     *
     * @return true if the window is maximized, false otherwise
     */
    public boolean isWindowMaximized() {
        return windowMaximized;
    }

    /**
     * Sets whether the window is maximized.
     *
     * @param windowMaximized
     *            true to maximize the window, false otherwise
     */
    public void setWindowMaximized(boolean windowMaximized) {
        this.windowMaximized = windowMaximized;
    }
}
