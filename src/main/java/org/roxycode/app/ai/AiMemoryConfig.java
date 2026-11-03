package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.List;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
public class AiMemoryConfig {

    static final int DEFAULT_MAX_MESSAGES = 50;

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, SettingsService settingsService) {
        return new ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                createDelegate(repository, settingsService).add(conversationId, messages);
                alignStoredMessages(repository, conversationId);
            }

            @Override
            public List<Message> get(String conversationId) {
                Assert.hasText(conversationId, "conversationId cannot be null or empty");
                int maxMessages = resolveMaxMessages(settingsService);
                List<Message> existing = repository.findByConversationId(conversationId);
                if (existing.size() > maxMessages) {
                    MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(maxMessages).build()
                            .add(conversationId, List.of());
                }
                return alignStoredMessages(repository, conversationId);
            }

            @Override
            public void clear(String conversationId) {
                createDelegate(repository, settingsService).clear(conversationId);
            }
        };
    }

    private static MessageWindowChatMemory createDelegate(ChatMemoryRepository repository,
            SettingsService settingsService) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository)
                .maxMessages(resolveMaxMessages(settingsService)).build();
    }

    private static List<Message> alignStoredMessages(ChatMemoryRepository repository, String conversationId) {
        List<Message> stored = repository.findByConversationId(conversationId);
        List<Message> aligned = alignToFirstUserMessage(stored);
        if (aligned.size() != stored.size()) {
            repository.saveAll(conversationId, aligned);
        }
        return aligned;
    }

    static List<Message> alignToFirstUserMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (!containsUserMessage(messages)) {
            return List.copyOf(messages);
        }
        boolean seenUserMessage = false;
        List<Message> aligned = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                aligned.add(message);
            } else if (!seenUserMessage) {
                if (message instanceof UserMessage) {
                    seenUserMessage = true;
                    aligned.add(message);
                }
            } else {
                aligned.add(message);
            }
        }
        return List.copyOf(aligned);
    }

    private static boolean containsUserMessage(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof UserMessage) {
                return true;
            }
        }
        return false;
    }

    static int resolveMaxMessages(SettingsService settingsService) {
        if (settingsService == null || settingsService.getSettings() == null) {
            return DEFAULT_MAX_MESSAGES;
        }
        int max = settingsService.getSettings().getMaxChatMemoryMessages();
        if (max > 0) {
            return max;
        }
        return DEFAULT_MAX_MESSAGES;
    }
}
