package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.ExploreManager;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.events.TurnEventBridge;
import org.springframework.ai.chat.memory.ChatMemory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.awt.Component;

public class ChatPanelTest {

        @Test
    public void testChatPanelInitialization() {
        AiService aiService = mock(AiService.class);
        ExploreManager exploreManager = mock(ExploreManager.class);
        JexlTool jexlTool = mock(JexlTool.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        
        ChatPanel chatPanel = new ChatPanel(aiService, exploreManager, jexlTool, workflowService, turnEventBridge, chatMemory);
        assertNotNull(chatPanel.getOutputArea(), "Output area should be initialized");
        assertNotNull(chatPanel.getInputArea(), "Input area should be initialized");
        assertTrue(chatPanel.getInputArea() instanceof JTextArea, "Input area should be a JTextArea");
        assertNotNull(chatPanel.getSendButton(), "Send button should be initialized");
        assertNotNull(chatPanel.getStopButton(), "Stop button should be initialized");
        assertEquals("Send", chatPanel.getSendButton().getText());
        // Note: stopButton might not have text depending on FontIcon usage
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

        @Test
    public void testContextMenuListeners() {
        AiService aiService = mock(AiService.class);
        ExploreManager exploreManager = mock(ExploreManager.class);
        JexlTool jexlTool = mock(JexlTool.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        
        ChatPanel chatPanel = new ChatPanel(aiService, exploreManager, jexlTool, workflowService, turnEventBridge, chatMemory);
        assertTrue(chatPanel.getInputArea().getMouseListeners().length > 0, "Input area should have mouse listeners for context menu");
        assertTrue(chatPanel.getOutputArea().getMouseListeners().length > 0, "Output area should have mouse listeners for context menu");
    }
}
