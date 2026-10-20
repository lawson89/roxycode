package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.events.UserMessageEvent;
import org.springframework.ai.chat.memory.ChatMemory;

public class HistoryPanelTest {

    @Test
    public void testHistoryPanelInitialization() {
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);

        ChatMemory chatMemory = mock(ChatMemory.class);
        HistoryPanel historyPanel = new HistoryPanel(turnEventBridge, chatMemory, "test");

        // Verify listeners registered
        verify(turnEventBridge).subscribe(eq(UserMessageEvent.class), eq("test:default"), any());
        verify(turnEventBridge)
                .subscribe(eq(AgentTurnCompleteEvent.class), eq("test:default"), any());
        verify(turnEventBridge).subscribe(eq(ToolExecutionEvent.class), eq("test:default"), any());

        // Verify layout
        boolean hasScrollPane = false;
        for (Component comp : historyPanel.getComponents()) {
            if (comp instanceof JScrollPane) {
                hasScrollPane = true;
                break;
            }
        }
        assertTrue(hasScrollPane, "HistoryPanel should contain a JScrollPane");
    }
}
