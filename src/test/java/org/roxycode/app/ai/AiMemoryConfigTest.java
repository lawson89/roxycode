package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class AiMemoryConfigTest {

    private static ChatMemory createMemory() {
        SettingsService settingsService = mock(SettingsService.class);
        AppSettings settings = new AppSettings();
        settings.setMaxChatMemoryMessages(50);
        when(settingsService.getSettings()).thenReturn(settings);
        return new AiMemoryConfig().chatMemory(new InMemoryChatMemoryRepository(), settingsService);
    }

    @Test
    void testSlidingWindowEviction() {
        ChatMemory memory = createMemory();
        String conversationId = "test-session";
        for (int i = 0; i < 100; i++) {
            memory.add(conversationId, List.of(new UserMessage("Msg " + i)));
        }

        List<Message> result = memory.get(conversationId);
        assertEquals(50, result.size());
        assertEquals("Msg 50", result.get(0).getText());
        assertEquals("Msg 99", result.get(49).getText());
    }

    @Test
    void testConcurrentAdditionsWithEviction() throws InterruptedException {
        ChatMemory memory = createMemory();
        String conversationId = "test-session";
        int numThreads = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);

        try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                executor.submit(
                        () -> {
                            try {
                                for (int j = 0; j < 100; j++) {
                                    memory.add(
                                            conversationId,
                                            List.of(new UserMessage("Msg " + threadId + "-" + j)));
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }
            latch.await(5, TimeUnit.SECONDS);
        }
        assertEquals(50, memory.get(conversationId).size());
    }
}
