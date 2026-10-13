package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.ActionEvent;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.service.AiService;
import org.springframework.ai.chat.memory.ChatMemory;

public class ChatPanelStopButtonTest {

  @Test
  public void testStopButtonEnablement() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);
    JButton stopButton = chatPanel.getStopButton();
    JButton sendButton = chatPanel.getSendButton();
    JTextArea inputArea = chatPanel.getInputArea();

    assertFalse(stopButton.isEnabled(), "Stop button should be disabled initially");
    assertTrue(sendButton.isEnabled(), "Send button should be enabled initially");

    inputArea.setText("test message");

    for (java.awt.event.ActionListener al : sendButton.getActionListeners()) {
      al.actionPerformed(new ActionEvent(sendButton, ActionEvent.ACTION_PERFORMED, null));
    }

    assertTrue(stopButton.isEnabled(), "Stop button should be enabled while sending");
    assertFalse(sendButton.isEnabled(), "Send button should be disabled while sending");
    assertFalse(inputArea.isEnabled(), "Input area should be disabled while sending");
  }
}
