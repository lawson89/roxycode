package org.roxycode.app.service;

import org.roxycode.app.ai.JexlServiceRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

/**
 * Service for interacting with the AI Chat Model.
 */
@Service
public class AiService {

    private final ChatModel chatModel;
    private final SettingsService settingsService;
    private final JexlServiceRegistry jexlServiceRegistry;

    public AiService(ChatModel chatModel, SettingsService settingsService, JexlServiceRegistry jexlServiceRegistry) {
        this.chatModel = chatModel;
        this.settingsService = settingsService;
        this.jexlServiceRegistry = jexlServiceRegistry;
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
        
        String finalSystemPrompt = (systemPromptText != null ? systemPromptText : "You are Roxy, an AI pair programmer.") 
                + "\n\nYou have access to the following JEXL tools:\n" + jexlDocs;
        
        SystemMessage systemMessage = new SystemMessage(finalSystemPrompt);
        UserMessage userMessage = new UserMessage(message);
        
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(activeModel)
                .build();
        
        // Try setting functions on the options object if builder method is missing
        
        
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage), options);
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}