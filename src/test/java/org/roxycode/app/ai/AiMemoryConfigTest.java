package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiMemoryConfigTest {

    @Test
    void testConcurrentAdditions() throws InterruptedException {
        AiMemoryConfig config = new AiMemoryConfig();
        ChatMemory memory = config.chatMemory();
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
        assertEquals(numThreads * messagesPerThread, result.size(), "Should contain all messages from all threads");
    }
}
