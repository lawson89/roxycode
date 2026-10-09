package org.roxycode.app.ai;

import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiMemoryConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, SettingsService settingsService) {
        return new ChatMemory() {
            private MessageWindowChatMemory getWindow() {
                int max = settingsService.getSettings().getMaxChatMemoryMessages();
                return MessageWindowChatMemory.builder()
                        .chatMemoryRepository(repository)
                        .maxMessages(max > 0 ? max : 50)
                        .build();
            }

            @Override
            public void add(String conversationId, List<Message> messages) {
                getWindow().add(conversationId, messages);
            }

            @Override
            public List<Message> get(String conversationId) {
                return getWindow().get(conversationId);
            }

            @Override
            public void clear(String conversationId) {
                getWindow().clear(conversationId);
            }
        };
    }
}
