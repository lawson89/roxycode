package org.roxycode.app.ai.services.cache;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@AgentService("geminiCacheService")
@AgentDoc("Manages Gemini API context caching indicators.")
public class GeminiCacheService {
    private static final Logger log = LoggerFactory.getLogger(GeminiCacheService.class);
    private final SettingsService settingsService;

    public GeminiCacheService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public long estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 4;
    }
}
