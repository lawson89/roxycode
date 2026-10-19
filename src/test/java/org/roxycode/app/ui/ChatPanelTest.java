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
    javax.swing.Action sendMessageAction =
        chatPanel.getInputArea().getActionMap().get("send-message");
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

  @Test
  public void testTransitionRequestedDuringWorkerAppendsAfterAiResponse() throws Exception {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    org.mockito.ArgumentCaptor<WorkflowService.TransitionRequestListener> listenerCaptor =
        org.mockito.ArgumentCaptor.forClass(WorkflowService.TransitionRequestListener.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);

    verify(workflowService).addTransitionRequestListener(listenerCaptor.capture());
    WorkflowService.TransitionRequestListener transitionListener = listenerCaptor.getValue();

    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
    when(aiService.chat(anyString(), any(), anyString(), any(), any()))
        .thenAnswer(
            inv -> {
              transitionListener.onTransitionRequested(
                  WorkflowPhase.PLAN, WorkflowPhase.CODE, "Plan ready");
              return "FINAL_PLAN_SUMMARY";
            });

    chatPanel.getInputArea().setText("Build feature");
    chatPanel.getSendButton().doClick();

    Thread.sleep(500);
    javax.swing.SwingUtilities.invokeAndWait(() -> {});

    String renderedHtml = chatPanel.getOutputArea().getText();
    int aiResponseIndex = renderedHtml.indexOf("FINAL_PLAN_SUMMARY");
    int transitionMsgIndex = renderedHtml.indexOf("Roxy requested a transition");

    assertTrue(aiResponseIndex >= 0, "AI response should be rendered");
    assertTrue(transitionMsgIndex >= 0, "Transition request message should be rendered");
    assertTrue(
        aiResponseIndex < transitionMsgIndex,
        "AI response should appear before the transition request prompt");
  }

  @Test
  public void testFocusInputSetsCaretToEnd() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);

    chatPanel.getInputArea().setText("Existing text in input");
    chatPanel.getInputArea().setCaretPosition(0);
    assertEquals(0, chatPanel.getInputArea().getCaretPosition());

    chatPanel.focusInput();

    assertEquals(
        "Existing text in input".length(),
        chatPanel.getInputArea().getCaretPosition(),
        "Caret should be moved to the end of the text");
  }

  @Test
  public void testOutputScrollPaneAndWrappingConfiguration() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "test", null, true);

    assertTrue(
        chatPanel.getOutputArea().getScrollableTracksViewportWidth(),
        "MarkdownPane should track viewport width");

    javax.swing.JScrollPane scrollPane =
        (javax.swing.JScrollPane)
            javax.swing.SwingUtilities.getAncestorOfClass(
                javax.swing.JScrollPane.class, chatPanel.getOutputArea());
    assertNotNull(scrollPane, "Output area should be inside a JScrollPane");
    assertSame(
        chatPanel.getOutputArea(),
        scrollPane.getViewport().getView(),
        "Output area should be the direct viewport view of outputScrollPane");
    assertEquals(
        javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        scrollPane.getHorizontalScrollBarPolicy(),
        "Horizontal scrollbar policy should be HORIZONTAL_SCROLLBAR_NEVER");

    java.awt.Container outputWrapper = scrollPane.getParent();
    assertNotNull(outputWrapper, "outputScrollPane should have an outputWrapper parent");
    assertTrue(
        outputWrapper.getLayout() instanceof java.awt.BorderLayout,
        "outputWrapper should use BorderLayout");
    java.awt.BorderLayout layout = (java.awt.BorderLayout) outputWrapper.getLayout();
    assertNotNull(
        layout.getLayoutComponent(java.awt.BorderLayout.SOUTH),
        "inlineActionPanel should be attached at BorderLayout.SOUTH of outputWrapper in task mode");
  }

  @Test
  public void testNonTaskChatPanelDoesNotRegisterWorkflowListeners() {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel qaChatPanel =
        new ChatPanel(
            aiService,
            workflowService,
            turnEventBridge,
            chatMemory,
            "qa",
            WorkflowPhase.EXPLORE,
            false);
    assertNotNull(qaChatPanel);

    verify(workflowService, never()).addPhaseListener(any());
    verify(workflowService, never()).addTransitionRequestListener(any());
    verify(turnEventBridge, never()).addAutonomousTransitionListener(any());
  }

  @Test
  public void testApproveCodeToPlanTriggersTurnWhileReviewToPlanCompletesTask() throws Exception {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "task", null, true);

    java.lang.reflect.Field approveBtnField = ChatPanel.class.getDeclaredField("approveButton");
    approveBtnField.setAccessible(true);
    javax.swing.JButton approveButton = (javax.swing.JButton) approveBtnField.get(chatPanel);

    // Case 1: CODE -> PLAN (plan revision) should trigger next turn in PLAN
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
    when(workflowService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);
    doAnswer(
            inv -> {
              when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
              return null;
            })
        .when(workflowService)
        .approveTransition();

    approveButton.setEnabled(true);
    approveButton.doClick();
    Thread.sleep(400);
    javax.swing.SwingUtilities.invokeAndWait(() -> {});

        verify(aiService, atLeastOnce())
        .chat(
            eq("task:default"),
            eq(WorkflowPhase.PLAN),
            contains("I approve the transition to Plan"),
            isNull(),
            any());
    verify(chatMemory, never()).clear(anyString());
    assertFalse(chatPanel.getOutputArea().getText().contains("Task complete"));

    // Case 2: REVIEW -> PLAN (completion) should show Task complete and not trigger
    // a new AI turn
    clearInvocations(aiService);
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
    when(workflowService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);

    approveButton.setEnabled(true);
    approveButton.doClick();
    Thread.sleep(300);
    javax.swing.SwingUtilities.invokeAndWait(() -> {});

    verify(aiService, never()).chat(anyString(), any(), anyString(), any(), any());
    verify(chatMemory).clear("task:default");
    assertTrue(chatPanel.getOutputArea().getText().contains("Task complete"));
  }

  @Test
  public void testSetLoadingRespectsPendingPhaseForApproveAndRejectButtons() throws Exception {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "task", null, true);

    java.lang.reflect.Field approveBtnField = ChatPanel.class.getDeclaredField("approveButton");
    approveBtnField.setAccessible(true);
    javax.swing.JButton approveButton = (javax.swing.JButton) approveBtnField.get(chatPanel);

    java.lang.reflect.Field rejectBtnField = ChatPanel.class.getDeclaredField("rejectButton");
    rejectBtnField.setAccessible(true);
    javax.swing.JButton rejectButton = (javax.swing.JButton) rejectBtnField.get(chatPanel);

    java.lang.reflect.Method setLoadingMethod =
        ChatPanel.class.getDeclaredMethod("setLoading", boolean.class);
    setLoadingMethod.setAccessible(true);

    // Initially disabled
    assertFalse(approveButton.isEnabled(), "approveButton should be disabled initially");
    assertFalse(rejectButton.isEnabled(), "rejectButton should be disabled initially");

    // When loading=false and no pending phase, buttons remain disabled
    when(workflowService.getPendingPhase()).thenReturn(null);
    setLoadingMethod.invoke(chatPanel, false);
    assertFalse(
        approveButton.isEnabled(),
        "approveButton should remain disabled when setLoading(false) and no pending phase");
    assertFalse(
        rejectButton.isEnabled(),
        "rejectButton should remain disabled when setLoading(false) and no pending phase");

    // When a pending phase exists and loading=true, buttons are disabled during
    // loading
    when(workflowService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
    setLoadingMethod.invoke(chatPanel, true);
    assertFalse(approveButton.isEnabled(), "approveButton should be disabled during loading");
    assertFalse(rejectButton.isEnabled(), "rejectButton should be disabled during loading");

    // When loading=false and a pending phase exists, buttons become enabled
    setLoadingMethod.invoke(chatPanel, false);
    assertTrue(
        approveButton.isEnabled(),
        "approveButton should be enabled when setLoading(false) and pending phase exists");
    assertTrue(
        rejectButton.isEnabled(),
        "rejectButton should be enabled when setLoading(false) and pending phase exists");
  }

  @Test
  public void testLoadingListenerNotified() throws Exception {
    AiService aiService = mock(AiService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
    ChatMemory chatMemory = mock(ChatMemory.class);

    ChatPanel chatPanel =
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "task", null, true);

    java.util.List<Boolean> states = new java.util.ArrayList<>();
    chatPanel.addLoadingListener(states::add);

    java.lang.reflect.Method setLoadingMethod =
        ChatPanel.class.getDeclaredMethod("setLoading", boolean.class);
    setLoadingMethod.setAccessible(true);

    setLoadingMethod.invoke(chatPanel, true);
    setLoadingMethod.invoke(chatPanel, false);

    assertEquals(java.util.List.of(true, false), states);
  }
}
