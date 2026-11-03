package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class AiMemoryConfigTest {

    @Test
    void testApplyWindowAlignsEvictionToUserMessageBoundariesAndPreservesSystemMessages() {
        assertTrue(AiMemoryConfig.applyWindow(null, 5).isEmpty());
        assertTrue(AiMemoryConfig.applyWindow(List.of(), 5).isEmpty());

        SystemMessage sys = new SystemMessage("sys");
        UserMessage u1 = new UserMessage("u1");
        AssistantMessage a1 = new AssistantMessage("a1");
        UserMessage u2 = new UserMessage("u2");
        AssistantMessage a2 = new AssistantMessage("a2");

        List<Message> oddAllowanceWindow = AiMemoryConfig.applyWindow(List.of(sys, u1, a1, u2, a2), 4);
        assertEquals(List.of(sys, u2, a2), oddAllowanceWindow);

        ToolResponseMessage toolResp = ToolResponseMessage.builder().responses(List.of()).build();
        List<Message> withToolChain = AiMemoryConfig.applyWindow(List.of(sys, u1, a1, toolResp, a1, u2, a2), 5);
        assertEquals(List.of(sys, u2, a2), withToolChain);

        List<Message> noRemainingUser = AiMemoryConfig.applyWindow(List.of(sys, u1, a1), 2);
        assertEquals(List.of(sys, a1), noRemainingUser);

        SystemMessage sys2 = new SystemMessage("sys2");
        List<Message> multipleSystemOnly = AiMemoryConfig.applyWindow(List.of(sys, sys2, u1, a1), 1);
        assertEquals(List.of(sys, sys2), multipleSystemOnly);
    }
}
