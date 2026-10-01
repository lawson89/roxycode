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
}