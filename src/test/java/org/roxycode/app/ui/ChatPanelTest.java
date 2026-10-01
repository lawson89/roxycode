package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.service.AiService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChatPanelTest {

    @Test
    public void testChatPanelInitialization() {
        AiService aiService = mock(AiService.class);
        ExploreManager exploreManager = mock(ExploreManager.class);
        JexlTool jexlTool = mock(JexlTool.class);
        ChatPanel chatPanel = new ChatPanel(aiService, exploreManager, jexlTool);
        assertNotNull(chatPanel.getOutputArea(), "Output area should be initialized");
        assertNotNull(chatPanel.getInputArea(), "Input area should be initialized");
        assertNotNull(chatPanel.getSendButton(), "Send button should be initialized");
        assertNotNull(chatPanel.getStopButton(), "Stop button should be initialized");
        assertFalse(chatPanel.getOutputArea().isEditable(), "Output area should not be editable");
    }
}
