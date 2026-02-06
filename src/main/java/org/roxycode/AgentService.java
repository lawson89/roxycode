package org.roxycode;

import jakarta.inject.Singleton;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

@Singleton
public class AgentService {
    private final ConfigService configService;
    private Client client;

    public AgentService(ConfigService configService) {
        this.configService = configService;
    }

    private synchronized Client getClient() {
        if (client == null) {
            client = Client.builder()
                    .apiKey(configService.getApiKey())
                    .build();
        }
        return client;
    }

    public String chat(String prompt) {
        try {
            GenerateContentResponse response = getClient().models.generateContent(
                    configService.getModelName(),
                    prompt,
                    null
            );
            return response.text();
        } catch (Exception e) {
            throw new RuntimeException("Failed to chat with Gemini: " + e.getMessage(), e);
        }
    }
}
