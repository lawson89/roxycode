package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.service.AiService;
import org.springframework.ai.chat.memory.ChatMemory;

public class ChatPanelTest {

  @Test
  public void testChatPanelInitialization() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);
    assertNotNull(chatPanel.getOutputArea(), "Output area should be initialized");
    assertNotNull(chatPanel.getInputArea(), "Input area should be initialized");
    assertTrue(chatPanel.getInputArea() instanceof JTextArea, "Input area should be a JTextArea");
    assertNotNull(chatPanel.getSendButton(), "Send button should be initialized");
    assertNotNull(chatPanel.getStopButton(), "Stop button should be initialized");
    assertEquals("Send", chatPanel.getSendButton().getText());
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
    WorkflowService workflowService = mock(WorkflowService.class);
    TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);
    assertTrue(
        chatPanel.getInputArea().getMouseListeners().length > 0,
        "Input area should have mouse listeners for context menu");
    assertTrue(
        chatPanel.getOutputArea().getMouseListeners().length > 0,
        "Output area should have mouse listeners for context menu");
  }

  @Test
  public void testSendMessageWithNullEventDoesNotThrowNPE() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);

    // Simulate keyboard shortcut which previously passed null
    javax.swing.Action sendMessageAction = chatPanel.getInputArea().getActionMap().get("send-message");
    assertNotNull(sendMessageAction, "send-message action should exist");

    // Setting some text so it doesn't return early
    chatPanel.getInputArea().setText("Hello");

    // This should not throw NPE
    assertDoesNotThrow(() -> sendMessageAction.actionPerformed(null));
  }
}
