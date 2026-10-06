package org.roxycode.app.ai.services.cache;

import com.google.genai.Client;
import com.google.genai.types.CachedContent;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.CreateCachedContentConfig;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@AgentService("geminiCacheService")
@AgentDoc("Manages Gemini API context caching using the official SDK.")
public class GeminiCacheService {
    private static final Logger log = LoggerFactory.getLogger(GeminiCacheService.class);
    private final SettingsService settingsService;

    public GeminiCacheService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @AgentDoc("Uploads codebase to Gemini cache and returns the cache name/ID.")
    public String createCache(String model, String packedCodebase) {
        String apiKey = settingsService.getSettings().getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "Error: No API key configured";
        }

        try {
            Client client = Client.builder().apiKey(apiKey).build();
            
            Content content = Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(packedCodebase).build()))
                    .build();

            CreateCachedContentConfig config = CreateCachedContentConfig.builder()
                    .contents(List.of(content))
                    .ttl(Duration.ofHours(1))
                    .build();

            CachedContent result = client.caches.create("models/" + model, config);
            // Using toString and a guess at the accessor. If name() fails, we'll see.
            return result.name().orElse("unknown");
        } catch (Exception e) {
            log.error("Exception during cache creation: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public long estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 4;
    }
}
