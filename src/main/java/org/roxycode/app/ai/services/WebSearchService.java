package org.roxycode.app.ai.services;

import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@AgentService(value = "webSearchService", phases = {"*"})
@AgentDoc("Provides grounded web search capabilities using Google Search retrieval to look up live documentation, release notes, APIs, and current facts.")
public class WebSearchService {

    private final AiService aiService;
    private final SettingsService settingsService;

    public WebSearchService(@Lazy AiService aiService, SettingsService settingsService) {
        this.aiService = aiService;
        this.settingsService = settingsService;
    }

    @AgentDoc(value = "Performs a live web search using Google Search Grounding and returns a synthesized answer with grounded facts and citations.", examples = {
            "webSearchService.search('Spring AI 2.0 Google GenAI options')"}, phases = {"*"})
    public EditorResult search(@AgentDoc("The search query or question to look up on the web") String query) {
        if (settingsService == null || settingsService.getSettings() == null
                || !settingsService.getSettings().isGoogleGroundingEnabled()) {
            return new EditorResult(false, "",
                    "Google Search Grounding is disabled in Settings. Enable 'Google Search Grounding' in Settings to use webSearchService.");
        }
        String trimmedQuery = StringUtils.trimToEmpty(query);
        if (trimmedQuery.isEmpty()) {
            return new EditorResult(false, "", "Search query must not be blank.");
        }
        try {
            String response = aiService.groundedSearch(trimmedQuery);
            if (StringUtils.isBlank(response)) {
                return new EditorResult(false, "", "Web search returned an empty response for query: " + trimmedQuery);
            }
            return new EditorResult(true, response, null);
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Web search cancelled");
            return new EditorResult(false, "", "Web search failed: " + ExceptionUtils.getRootCauseMessage(e));
        }
    }
}
