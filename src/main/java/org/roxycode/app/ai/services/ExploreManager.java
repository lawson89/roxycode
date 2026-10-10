package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.springframework.stereotype.Service;

/**
 * Manages system prompts and context for the EXPLORE phase.
 */
@Service
@AgentService(value = "exploreManager", phases = {"EXPLORE"})
public class ExploreManager {

    private final SettingsService settingsService;
    private final PromptService promptService;

    public ExploreManager(SettingsService settingsService, PromptService promptService) {
        this.settingsService = settingsService;
        this.promptService = promptService;
    }

    /**
     * Generates the system prompt for exploration.
     */
    public String generateSystemPrompt() {
        String prompt = promptService.loadExplorePrompt();
        String substituted = prompt.replace("{{MAX_TURNS}}", String.valueOf(settingsService.getSettings().getMaxAgentToolTurns()));
        return "You are the Technical Mentor. Provides technical guidance and ensures adherence to best practices." + substituted;
    }
}
