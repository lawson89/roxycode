package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

public class AppSettings {

    public static final String DEFAULT_THEME = "FlatLaf Light";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";
    public static final int DEFAULT_MAX_AGENT_TOOL_TURNS = 50;
    public static final String DEFAULT_STT_MODEL = "gemini-3.5-transcribe";
    public static final String DEFAULT_TTS_MODEL = "gemini-3.8-flash-lite-tts";
    public static final String DEFAULT_TTS_VOICE = "Kore";
    public static final List<String> PREBUILT_TTS_VOICES = List.of("Kore", "Aoede", "Puck", "Charon", "Fenrir", "Leda",
            "Orus", "Zephyr");

    private String theme = DEFAULT_THEME;
    private String geminiApiKey = "";
    private String currentProjectPath = "";
    private String geminiModel = DEFAULT_GEMINI_MODEL;
    private int maxAgentToolTurns = DEFAULT_MAX_AGENT_TOOL_TURNS;
    private int maxChatMemoryMessages = 50;
    private boolean logToolCalls = false;
    private String sttModel = DEFAULT_STT_MODEL;
    private String ttsModel = DEFAULT_TTS_MODEL;
    private String ttsVoice = DEFAULT_TTS_VOICE;
    private boolean autoSpeakResponses = false;
    private List<String> globalContextFolders = new ArrayList<>();

    public AppSettings() {
    }

    public AppSettings(AppSettings other) {
        if (other != null) {
            this.theme = other.theme;
            this.geminiApiKey = other.geminiApiKey;
            this.currentProjectPath = other.currentProjectPath;
            this.geminiModel = other.geminiModel;
            this.maxAgentToolTurns = other.maxAgentToolTurns;
            this.maxChatMemoryMessages = other.maxChatMemoryMessages;
            this.logToolCalls = other.logToolCalls;
            this.sttModel = other.sttModel;
            this.ttsModel = other.ttsModel;
            this.ttsVoice = other.ttsVoice;
            this.autoSpeakResponses = other.autoSpeakResponses;
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

    public String getSttModel() {
        return StringUtils.defaultIfBlank(sttModel, DEFAULT_STT_MODEL);
    }

    public void setSttModel(String sttModel) {
        this.sttModel = sttModel;
    }

    public String getTtsModel() {
        return StringUtils.defaultIfBlank(ttsModel, DEFAULT_TTS_MODEL);
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getTtsVoice() {
        return StringUtils.defaultIfBlank(ttsVoice, DEFAULT_TTS_VOICE);
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public boolean isAutoSpeakResponses() {
        return autoSpeakResponses;
    }

    public void setAutoSpeakResponses(boolean autoSpeakResponses) {
        this.autoSpeakResponses = autoSpeakResponses;
    }

    public List<String> getGlobalContextFolders() {
        return List.copyOf(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }

    public void setGlobalContextFolders(List<String> globalContextFolders) {
        this.globalContextFolders = new ArrayList<>(Objects.requireNonNullElse(globalContextFolders, List.of()));
    }
}
