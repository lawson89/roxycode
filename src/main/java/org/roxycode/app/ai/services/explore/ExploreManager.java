package org.roxycode.app.ai.services.explore;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.SettingsService;
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

    public ExploreManager(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @AgentDoc("Generates the system prompt for the Technical Mentor during the EXPLORE phase.")
    public String generateSystemPrompt() {
        int maxTurns = settingsService.getSettings().getMaxAgentToolTurns();
        return AgentRole.TECHNICAL_MENTOR.getSystemPromptPrefix() + """

                You are currently in the EXPLORE phase. Your mission is to assist the user in exploring and understanding the codebase.
                Key Instructions:
                1. Read-Only: You have read-only access to the project. Do not attempt to modify any files.
                2. Exploration Tools: Use the provided JEXL tools (e.g., fileReadService, gitService) to gather information.
                3. Tool Budget: You have a maximum of %d tool turns for autonomous exploration. Use them wisely.
                4. Source Attribution: Always cite file paths and line numbers for any code snippets or technical facts you present.
                5. Conciseness: Be objective and concise. Avoid unnecessary preamble.
                6. Focus: Provide technical insights, architectural overviews, or clarify logic as requested.""".formatted(maxTurns);
    }
}