package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.List;

/** Model representing application settings. */
public class AppSettings {
    public static final String DEFAULT_THEME = "FlatLaf Light";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";

    private String theme = DEFAULT_THEME;
    private String geminiApiKey = "";
    private String currentProjectPath = "";
    private String geminiModel = DEFAULT_GEMINI_MODEL;
    private int maxAgentToolTurns = 5;
    private int maxChatMemoryMessages = 50;
    private List<String> globalContextFolders = new ArrayList<>();

    public AppSettings() {}

    public String getTheme() {
        return theme;
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
        return geminiModel;
    }

    public void setGeminiModel(String geminiModel) {
        this.geminiModel = geminiModel;
    }

    /**
     * Gets the maximum number of turns the agent can use tools during autonomous execution.
     *
     * @return the max agent tool turns
     */
    public int getMaxAgentToolTurns() {
        return maxAgentToolTurns;
    }

    /**
     * Sets the maximum number of turns the agent can use tools during autonomous execution.
     *
     * @param maxAgentToolTurns the max agent tool turns to set
     */
    public void setMaxAgentToolTurns(int maxAgentToolTurns) {
        this.maxAgentToolTurns = maxAgentToolTurns;
    }

    /**
     * Gets the maximum number of messages to keep in chat history.
     *
     * @return the max messages
     */
    public int getMaxChatMemoryMessages() {
        return maxChatMemoryMessages;
    }

    /**
     * Sets the maximum number of messages to keep in chat history.
     *
     * @param maxChatMemoryMessages the max messages to set
     */
    public void setMaxChatMemoryMessages(int maxChatMemoryMessages) {
        this.maxChatMemoryMessages = maxChatMemoryMessages;
    }

    /**
     * Gets the configured global context folder paths.
     *
     * @return immutable list of global context folder paths
     */
    public List<String> getGlobalContextFolders() {
        return globalContextFolders == null ? List.of() : List.copyOf(globalContextFolders);
    }

    /**
     * Sets the configured global context folder paths.
     *
     * @param globalContextFolders the list of folder paths to set
     */
    public void setGlobalContextFolders(List<String> globalContextFolders) {
        this.globalContextFolders =
                globalContextFolders == null
                        ? new ArrayList<>()
                        : new ArrayList<>(globalContextFolders);
    }
}
