package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.service.AiService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.swing.JSplitPane;
import java.awt.Component;

public class ChatPanelTest {

    @Test
    public void testChatPanelInitialization() {
        AiService aiService = mock(AiService.class);
        ExploreManager exploreManager = mock(ExploreManager.class);
        JexlTool jexlTool = mock(JexlTool.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        
        ChatPanel chatPanel = new ChatPanel(aiService, exploreManager, jexlTool, workflowService);
        assertNotNull(chatPanel.getOutputArea(), "Output area should be initialized");
        assertNotNull(chatPanel.getInputArea(), "Input area should be initialized");
        assertNotNull(chatPanel.getSendButton(), "Send button should be initialized");
        assertNotNull(chatPanel.getStopButton(), "Stop button should be initialized");
        assertEquals("Send", chatPanel.getSendButton().getText());
        assertEquals("Stop", chatPanel.getStopButton().getText());
        assertFalse(chatPanel.getOutputArea().isEditable(), "Output area should not be editable");

        boolean hasSplitPane = false;
        for (Component comp : chatPanel.getComponents()) {
            if (comp instanceof JSplitPane) {
                hasSplitPane = true;
                break;
            }
        }
        assertTrue(hasSplitPane, "ChatPanel should contain a JSplitPane");
    }
}
