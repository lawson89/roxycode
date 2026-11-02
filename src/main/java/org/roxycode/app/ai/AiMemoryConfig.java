package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.List;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
                Assert.hasText(conversationId, "conversationId cannot be null or empty");
                Assert.notNull(messages, "messages cannot be null");
                Assert.noNullElements(messages, "messages cannot contain null elements");
                List<Message> existing = repository.findByConversationId(conversationId);
                List<Message> combined = combineMessages(existing, messages);
                List<Message> windowed = applyWindow(combined, resolveMaxMessages(settingsService));
                repository.saveAll(conversationId, windowed);
            }

            @Override
            public List<Message> get(String conversationId) {
                Assert.hasText(conversationId, "conversationId cannot be null or empty");
                List<Message> existing = repository.findByConversationId(conversationId);
                List<Message> windowed = applyWindow(existing, resolveMaxMessages(settingsService));
                if (windowed.size() != existing.size()) {
                    repository.saveAll(conversationId, windowed);
                }
                return windowed;
            }

            @Override
            public void clear(String conversationId) {
                Assert.hasText(conversationId, "conversationId cannot be null or empty");
                repository.deleteByConversationId(conversationId);
            }
        };
    }

    static List<Message> combineMessages(List<Message> existing, List<Message> newMessages) {
        boolean hasNewSystemMessage = false;
        for (Message message : newMessages) {
            if (message instanceof SystemMessage) {
                hasNewSystemMessage = true;
                break;
            }
        }
        List<Message> combined = new ArrayList<>(existing.size() + newMessages.size());
        for (Message message : existing) {
            if (hasNewSystemMessage && message instanceof SystemMessage && !newMessages.contains(message)) {
                continue;
            }
            combined.add(message);
        }
        combined.addAll(newMessages);
        return combined;
    }

    static List<Message> applyWindow(List<Message> messages, int maxMessages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (messages.size() <= maxMessages) {
            return List.copyOf(messages);
        }
        int systemCount = 0;
        int nonSystemCount = 0;
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                systemCount++;
            } else {
                nonSystemCount++;
            }
        }
        int nonSystemAllowance = Math.max(0, maxMessages - systemCount);
        int toEvict = Math.max(0, nonSystemCount - nonSystemAllowance);
        List<Message> result = new ArrayList<>(Math.min(messages.size(), Math.max(maxMessages, systemCount)));
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                result.add(message);
            } else if (toEvict > 0) {
                toEvict--;
            } else {
                result.add(message);
            }
        }
        return List.copyOf(result);
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
