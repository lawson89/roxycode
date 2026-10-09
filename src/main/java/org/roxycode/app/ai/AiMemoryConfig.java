package org.roxycode.app.ai;

import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiMemoryConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, SettingsService settingsService) {
        int max = settingsService.getSettings().getMaxChatMemoryMessages();
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(max > 0 ? max : 50)
                .build();
    }
}
