package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class AiMemoryConfigTest {

    @Test
    void testChatMemoryDelegatesWindowingSystemReplacementAndAlignsToFirstUserMessage() {
        assertTrue(AiMemoryConfig.alignToFirstUserMessage(null).isEmpty());
        assertTrue(AiMemoryConfig.alignToFirstUserMessage(List.of()).isEmpty());

        SystemMessage sys1 = new SystemMessage("sys1");
        UserMessage u1 = new UserMessage("u1");
        AssistantMessage a1 = new AssistantMessage("a1");
        UserMessage u2 = new UserMessage("u2");
        AssistantMessage a2 = new AssistantMessage("a2");
        ToolResponseMessage toolResp = ToolResponseMessage.builder().responses(List.of()).build();

        // Direct unit verification of post-eviction turn-boundary alignment
        assertEquals(List.of(sys1, u2, a2), AiMemoryConfig.alignToFirstUserMessage(List.of(sys1, a1, u2, a2)));
        assertEquals(List.of(sys1, u2, a2),
                AiMemoryConfig.alignToFirstUserMessage(List.of(sys1, toolResp, a1, u2, a2)));
        assertEquals(List.of(sys1, a1), AiMemoryConfig.alignToFirstUserMessage(List.of(sys1, a1)));

        AppSettings settings = new AppSettings();
        settings.setMaxChatMemoryMessages(4);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);

        AiMemoryConfig config = new AiMemoryConfig();
        ChatMemoryRepository repository = config.chatMemoryRepository();
        ChatMemory memory = config.chatMemory(repository, settingsService);

        // Window of 4 evicts u1, leaving [sys1, a1, u2, a2]; post-eviction alignment drops orphaned a1
        memory.add("conv-1", List.of(sys1, u1, a1, u2, a2));
        assertEquals(List.of(sys1, u2, a2), memory.get("conv-1"));

        // Replacing SystemMessage evicts previous SystemMessage via MessageWindowChatMemory
        SystemMessage sys2 = new SystemMessage("sys2");
        memory.add("conv-1", List.of(sys2));
        assertEquals(List.of(u2, a2, sys2), memory.get("conv-1"));

        // Tool chain alignment when eviction splits across AssistantMessage and ToolResponseMessage
        settings.setMaxChatMemoryMessages(5);
        memory.clear("conv-1");
        assertTrue(memory.get("conv-1").isEmpty());

        memory.add("conv-1", List.of(sys1, u1, a1, toolResp, a1, u2, a2));
        assertEquals(List.of(sys1, u2, a2), memory.get("conv-1"));
    }
}
