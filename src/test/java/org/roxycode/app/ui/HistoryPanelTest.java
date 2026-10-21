package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserMessageEvent;
import org.springframework.ai.chat.memory.ChatMemory;

public class HistoryPanelTest {

    @Test
    public void testHistoryPanelInitializationAndEventFiltering() throws Exception {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ExploreHistoryPanel qaHistory = new ExploreHistoryPanel(chatMemory);
        FeatureHistoryPanel taskHistory = new FeatureHistoryPanel(chatMemory);

        assertEquals("qa:default", qaHistory.getConversationId());
        assertEquals("task:default", taskHistory.getConversationId());

        SwingUtilities.invokeAndWait(() -> {});
        clearInvocations(chatMemory);

        // Events for matching conversationId should trigger refresh
        qaHistory.onUserMessage(new UserMessageEvent("qa:default", "user", "hi"));
        qaHistory.onAgentTurnComplete(new AgentTurnCompleteEvent("qa:default", "Roxy", 1, "done"));
        qaHistory.onToolExecution(new ToolExecutionEvent("qa:default", "1+1", 2, true, null));
        // Events for different conversationId should be ignored
        qaHistory.onUserMessage(new UserMessageEvent("task:default", "user", "other"));
        SwingUtilities.invokeAndWait(() -> {});

        verify(chatMemory, times(3)).get("qa:default");
        verify(chatMemory, never()).get("task:default");

        // Verify layout
        boolean hasScrollPane = false;
        for (Component comp : qaHistory.getComponents()) {
            if (comp instanceof JScrollPane) {
                hasScrollPane = true;
                break;
            }
        }
        assertTrue(hasScrollPane, "HistoryPanel should contain a JScrollPane");
    }
}
