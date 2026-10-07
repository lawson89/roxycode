package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.events.TurnEventBridge;
import javax.swing.*;
import java.awt.event.ActionEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChatPanelStopButtonTest {

    @Test
    public void testStopButtonEnablement() {
        AiService aiService = mock(AiService.class);
        ExploreManager exploreManager = mock(ExploreManager.class);
        JexlTool jexlTool = mock(JexlTool.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);

        ChatPanel chatPanel = new ChatPanel(aiService, exploreManager, jexlTool, workflowService, turnEventBridge);
        JButton stopButton = chatPanel.getStopButton();
        JButton sendButton = chatPanel.getSendButton();
        JTextArea inputArea = chatPanel.getInputArea();

        // Initially disabled
        assertFalse(stopButton.isEnabled(), "Stop button should be disabled initially");
        assertTrue(sendButton.isEnabled(), "Send button should be enabled initially");

        // Simulate sending a message
        inputArea.setText("test message");
        
        // sendMessage is private, but it's the action listener for sendButton
        for (java.awt.event.ActionListener al : sendButton.getActionListeners()) {
            al.actionPerformed(new ActionEvent(sendButton, ActionEvent.ACTION_PERFORMED, null));
        }

        // Now loading should be true
        assertTrue(stopButton.isEnabled(), "Stop button should be enabled while sending");
        assertFalse(sendButton.isEnabled(), "Send button should be disabled while sending");
        assertFalse(inputArea.isEnabled(), "Input area should be disabled while sending");
    }
}
