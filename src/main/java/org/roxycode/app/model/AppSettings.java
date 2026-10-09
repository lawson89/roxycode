package org.roxycode.app.model;

/**
 * Model representing application settings.
 */
public class AppSettings {
    private String theme = "FlatLaf Light";
    private String geminiApiKey = "";
    private String currentProjectPath = "";
    private String geminiModel = "gemini-1.5-flash";
    private int maxAgentToolTurns = 5;
    private int maxChatMemoryMessages = 50;

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
     * @return the max agent tool turns
     */
    public int getMaxAgentToolTurns() {
        return maxAgentToolTurns;
    }

    /**
     * Sets the maximum number of turns the agent can use tools during autonomous execution.
     * @param maxAgentToolTurns the max agent tool turns to set
     */
    public void setMaxAgentToolTurns(int maxAgentToolTurns) {
        this.maxAgentToolTurns = maxAgentToolTurns;
    }

    /**
     * Gets the maximum number of messages to keep in chat history.
     * @return the max messages
     */
    public int getMaxChatMemoryMessages() {
        return maxChatMemoryMessages;
    }

    /**
     * Sets the maximum number of messages to keep in chat history.
     * @param maxChatMemoryMessages the max messages to set
     */
    public void setMaxChatMemoryMessages(int maxChatMemoryMessages) {
        this.maxChatMemoryMessages = maxChatMemoryMessages;
    }
}
