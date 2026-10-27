package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.List;

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

    public List<String> getGlobalContextFolders() {
        return globalContextFolders == null ? List.of() : List.copyOf(globalContextFolders);
    }

    public void setGlobalContextFolders(List<String> globalContextFolders) {
        this.globalContextFolders =
                globalContextFolders == null
                        ? new ArrayList<>()
                        : new ArrayList<>(globalContextFolders);
    }
}
