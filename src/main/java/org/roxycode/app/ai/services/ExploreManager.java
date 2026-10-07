package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.PromptService;
import org.springframework.stereotype.Service;

/**
 * Service for the Technical Mentor role during the EXPLORE phase.
 * Provides system prompt generation for stateless exploration.
 */
@Service
@AgentService(value = "exploreManager", roles = {"TECHNICAL_MENTOR"})
@AgentDoc("Manages the EXPLORE phase and provides system prompts for technical exploration.")
public class ExploreManager {

    private final SettingsService settingsService;
    private final PromptService promptService;

    public ExploreManager(SettingsService settingsService, PromptService promptService) {
        this.settingsService = settingsService;
        this.promptService = promptService;
    }

    @AgentDoc("Generates the system prompt for the Technical Mentor during the EXPLORE phase.")
    public String generateSystemPrompt() {
        int maxTurns = settingsService.getSettings().getMaxAgentToolTurns();
        String template = promptService.loadExplorePrompt();
        return AgentRole.TECHNICAL_MENTOR.getSystemPromptPrefix() + template.formatted(maxTurns);
    }
}