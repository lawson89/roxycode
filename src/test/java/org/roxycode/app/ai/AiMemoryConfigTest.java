package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AiMemoryConfigTest {

    @Test
    void testSlidingWindowEviction() {
        SettingsService settingsService = mock(SettingsService.class);
        AppSettings settings = new AppSettings();
        settings.setMaxChatMemoryMessages(50);
        when(settingsService.getSettings()).thenReturn(settings);

        AiMemoryConfig config = new AiMemoryConfig();
        ChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        ChatMemory memory = config.chatMemory(repository, settingsService);
        String conversationId = "test-session";

        // Add 100 messages
        for (int i = 0; i < 100; i++) {
            memory.add(conversationId, List.of(new UserMessage("Msg " + i)));
        }

        List<Message> result = memory.get(conversationId);
        assertEquals(50, result.size(), "Should only contain the last 50 messages");
        assertEquals("Msg 50", result.get(0).getText(), "First message in window should be Msg 50");
        assertEquals("Msg 99", result.get(49).getText(), "Last message in window should be Msg 99");
    }

    @Test
    void testConcurrentAdditionsWithEviction() throws InterruptedException {
        SettingsService settingsService = mock(SettingsService.class);
        AppSettings settings = new AppSettings();
        settings.setMaxChatMemoryMessages(50);
        when(settingsService.getSettings()).thenReturn(settings);

        AiMemoryConfig config = new AiMemoryConfig();
        ChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        ChatMemory memory = config.chatMemory(repository, settingsService);
        String conversationId = "test-session";
        int numThreads = 10;
        int messagesPerThread = 100;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        memory.add(conversationId, List.of(new UserMessage("Msg " + threadId + "-" + j)));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        List<Message> result = memory.get(conversationId);
        assertEquals(50, result.size(), "Should only contain 50 messages despite 1,000 additions");
    }
}
