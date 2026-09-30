package org.roxycode.app.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

/**
 * Service for interacting with the AI Chat Model.
 */
@Service
public class AiService {

    private final ChatModel chatModel;

    public AiService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Sends a prompt to the AI and returns the response content.
     * @param message The user message.
     * @return The AI response content.
     */
    public String chat(String message) {
        return chatModel.call(message);
    }
}