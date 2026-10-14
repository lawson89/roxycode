package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.service.AiService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

public class ChatPanelTest {

  @Test
  public void testChatPanelInitialization() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
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
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
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
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
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

  @Test
  public void testAutonomousTransitionSuppressesTurnForPlanAndExplore() throws Exception {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);

    // Transition to PLAN
    turnEventBridge.handleAutonomousTransition(
        new AutonomousTransitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason"));

    // Give SwingUtilities.invokeLater a moment to run
    Thread.sleep(300);

    // chat should NOT be called for PLAN
    verify(aiService, never()).chat(anyString(), any(), anyString(), any(), any());

    // Transition to EXPLORE
    turnEventBridge.handleAutonomousTransition(
        new AutonomousTransitionEvent(WorkflowPhase.CODE, WorkflowPhase.EXPLORE, "Test reason"));
    Thread.sleep(300);
    verify(aiService, never()).chat(anyString(), any(), anyString(), any(), any());

    // Transition to REVIEW (should trigger turn)
    turnEventBridge.handleAutonomousTransition(
        new AutonomousTransitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason"));
    Thread.sleep(600); // Give it more time for SwingWorker

    verify(aiService, atLeastOnce()).chat(anyString(), any(), anyString(), isNull(), any());
  }
}