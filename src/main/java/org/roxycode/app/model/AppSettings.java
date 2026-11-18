package org.roxycode.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Global application settings for RoxyCode.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppSettings {

    public static final String DEFAULT_THEME = "FlatLaf Light";
    public static final String DEFAULT_AGENT_PROFILE = AgentProfile.DEFAULT.getId();
    public static final String DEFAULT_VERTEX_LOCATION = "global";
    public static final int DEFAULT_MAX_AGENT_TOOL_TURNS = 50;
    public static final int MAX_RECENT_PROJECTS = 5;
    public static final int DEFAULT_WINDOW_WIDTH = 1200;
    public static final int DEFAULT_WINDOW_HEIGHT = 900;

    private String theme = DEFAULT_THEME;
    private String geminiApiKey = "";
    private boolean vertexAiEnabled = false;
    private String vertexProjectId = "";
    private String vertexLocation = DEFAULT_VERTEX_LOCATION;
    private String vertexCredentialsPath = "";
    private String currentProjectPath = "";
    private List<String> recentProjectPaths = new ArrayList<>();
    private String agentProfile = DEFAULT_AGENT_PROFILE;
    private int maxAgentToolTurns = DEFAULT_MAX_AGENT_TOOL_TURNS;
    private int maxChatMemoryMessages = 50;
    private boolean googleGroundingEnabled = false;
    private Integer windowX;
    private Integer windowY;
    private int windowWidth = DEFAULT_WINDOW_WIDTH;
    private int windowHeight = DEFAULT_WINDOW_HEIGHT;
    private boolean windowMaximized = false;

    /** Creates settings initialized with default values. */
    public AppSettings() {
    }

    /** Creates a copy of the given settings. */
    public AppSettings(AppSettings other) {
        if (other != null) {
            this.theme = other.theme;
            this.geminiApiKey = other.geminiApiKey;
            this.vertexAiEnabled = other.vertexAiEnabled;
            this.vertexProjectId = other.vertexProjectId;
            this.vertexLocation = other.vertexLocation;
            this.vertexCredentialsPath = other.vertexCredentialsPath;
            this.currentProjectPath = other.currentProjectPath;
            this.recentProjectPaths = new ArrayList<>(Objects.requireNonNullElse(other.recentProjectPaths, List.of()));
            this.agentProfile = other.agentProfile;
            this.maxAgentToolTurns = other.maxAgentToolTurns;
            this.maxChatMemoryMessages = other.maxChatMemoryMessages;
            this.googleGroundingEnabled = other.googleGroundingEnabled;
            this.windowX = other.windowX;
            this.windowY = other.windowY;
            this.windowWidth = other.windowWidth;
            this.windowHeight = other.windowHeight;
            this.windowMaximized = other.windowMaximized;
        }
    }

    /** Returns a defensive copy of these settings. */
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

    public boolean isVertexAiEnabled() {
        return vertexAiEnabled;
    }

    public void setVertexAiEnabled(boolean vertexAiEnabled) {
        this.vertexAiEnabled = vertexAiEnabled;
    }

    public String getVertexProjectId() {
        return StringUtils.defaultString(vertexProjectId);
    }

    public void setVertexProjectId(String vertexProjectId) {
        this.vertexProjectId = StringUtils.defaultString(vertexProjectId);
    }

    public String getVertexLocation() {
        return StringUtils.defaultIfBlank(vertexLocation, DEFAULT_VERTEX_LOCATION);
    }

    public void setVertexLocation(String vertexLocation) {
        this.vertexLocation = StringUtils.defaultIfBlank(vertexLocation, DEFAULT_VERTEX_LOCATION);
    }

    public String getVertexCredentialsPath() {
        return StringUtils.defaultString(vertexCredentialsPath);
    }

    public void setVertexCredentialsPath(String vertexCredentialsPath) {
        this.vertexCredentialsPath = StringUtils.defaultString(vertexCredentialsPath);
    }

    public String getCurrentProjectPath() {
        return currentProjectPath;
    }

    public void setCurrentProjectPath(String currentProjectPath) {
        this.currentProjectPath = currentProjectPath;
    }

    public List<String> getRecentProjectPaths() {
        return recentProjectPaths;
    }

    public void setRecentProjectPaths(List<String> recentProjectPaths) {
        this.recentProjectPaths = new ArrayList<>(Objects.requireNonNullElse(recentProjectPaths, List.of()));
    }

    public String getAgentProfile() {
        return AgentProfile.fromString(agentProfile).getId();
    }

    public void setAgentProfile(String agentProfile) {
        this.agentProfile = AgentProfile.fromString(agentProfile).getId();
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

    public boolean isGoogleGroundingEnabled() {
        return googleGroundingEnabled;
    }

    public void setGoogleGroundingEnabled(boolean googleGroundingEnabled) {
        this.googleGroundingEnabled = googleGroundingEnabled;
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
