package org.roxycode.app.ai;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.messages.Message;

@Configuration
public class AiMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return new ChatMemory() {
            private final Map<String, List<Message>> memories = new ConcurrentHashMap<>();
            @Override
            public void add(String conversationId, List<Message> messages) {
                memories.computeIfAbsent(conversationId, k -> new ArrayList<>()).addAll(messages);
            }
            @Override
            public List<Message> get(String conversationId) {
                return memories.getOrDefault(conversationId, new ArrayList<>());
            }
            @Override
            public void clear(String conversationId) {
                memories.remove(conversationId);
            }
        };
    }
}
