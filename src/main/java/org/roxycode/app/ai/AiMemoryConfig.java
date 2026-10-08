package org.roxycode.app.ai;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.Message;

@Configuration
public class AiMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return new ChatMemory() {
            private final Map<String, List<Message>> memories = new ConcurrentHashMap<>();

            @Override
            public void add(String conversationId, List<Message> messages) {
                memories.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).addAll(messages);
            }

            @Override
            public List<Message> get(String conversationId) {
                List<Message> list = memories.get(conversationId);
                return list != null ? List.copyOf(list) : List.of();
            }

            @Override
            public void clear(String conversationId) {
                memories.remove(conversationId);
            }
        };
    }
}
