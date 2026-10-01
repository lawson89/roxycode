package org.roxycode.app.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * Service for interacting with the AI Chat Model.
 */
@Service
public class AiService {

    private final ChatModel chatModel;
    private final SettingsService settingsService;

    public AiService(ChatModel chatModel, SettingsService settingsService) {
        this.chatModel = chatModel;
        this.settingsService = settingsService;
    }

    /**
     * Sends a prompt to the AI and returns the response content.
     * @param message The user message.
     * @return The AI response content.
     */
    public String chat(String message) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        Prompt prompt = new Prompt(message, GoogleGenAiChatOptions.builder()
                .model(activeModel)
                .build());
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}