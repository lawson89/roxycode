package org.roxycode.app.service;

import org.roxycode.app.ai.JexlServiceRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;
import java.util.List;

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
        String activeModel = settingsService.getSettings().getGeminiModel();
        String jexlDocs = jexlServiceRegistry.getDocumentation();
        
        SystemMessage systemMessage = new SystemMessage("You have access to the following JEXL tools:\n" + jexlDocs);
        UserMessage userMessage = new UserMessage(message);
        
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage), GoogleGenAiChatOptions.builder()
                .model(activeModel)
                .build());
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}
