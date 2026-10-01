package org.roxycode.app.service;

import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * Service for interacting with the AI Chat Model using the modern ChatClient fluent API.
 */
@Service
public class AiService {

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final JexlServiceRegistry jexlServiceRegistry;

    public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService, 
                     JexlServiceRegistry jexlServiceRegistry, JexlTool jexlTool) {
        this.settingsService = settingsService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.chatClient = chatClientBuilder
                .defaultSystem("You are Roxy, an AI pair programmer.")
                .defaultTools(jexlTool)
                .build();
    }

    /**
     * Sends a prompt to the AI and returns the response content.
     * @param message The user message.
     * @return The AI response content.
     */
    public String chat(String message) {
        return chat(message, null);
    }

    /**
     * Sends a prompt to the AI with a custom system prompt and returns the response content.
     * @param message The user message.
     * @param systemPromptText The custom system prompt.
     * @return The AI response content.
     */
    public String chat(String message, String systemPromptText) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        String jexlDocs = jexlServiceRegistry.getDocumentation();
        
        String systemPrompt = (systemPromptText != null ? systemPromptText : "You are Roxy, an AI pair programmer.") 
                + "\n\nYou have access to the following JEXL tools:\n" + jexlDocs;
        
        return chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .options(GoogleGenAiChatOptions.builder().model(activeModel))
                .call()
                .content();
    }
}