package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.events.UserMessageEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.springframework.ai.chat.memory.ChatMemory;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.swing.JScrollPane;
import java.awt.Component;

public class HistoryPanelTest {

    @Test
    public void testHistoryPanelInitialization() {
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
        JexlTool jexlTool = mock(JexlTool.class);

        ChatMemory chatMemory = mock(ChatMemory.class);
        GeminiCacheService geminiCacheService = mock(GeminiCacheService.class);
        HistoryPanel historyPanel = new HistoryPanel(turnEventBridge, jexlTool, chatMemory, geminiCacheService);
        
        // Verify listeners registered
        verify(turnEventBridge).addUserMessageListener(any());
        verify(turnEventBridge).addCompleteListener(any());
        verify(jexlTool).addListener(historyPanel);

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
