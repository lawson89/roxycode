package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import java.lang.reflect.Method;
import java.util.Arrays;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.service.AiService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

public class ChatPanelTest {

    @Test
    public void testChatPanelInitialization() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel chatPanel =
                new ChatPanel(
                        aiService, planService, publisher, chatMemory, "test", null, true, null);
        assertNotNull(chatPanel.getOutputArea(), "Output area should be initialized");
        assertNotNull(chatPanel.getInputArea(), "Input area should be initialized");
        assertTrue(
                chatPanel.getInputArea() instanceof JTextArea, "Input area should be a JTextArea");
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
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel chatPanel =
                new ChatPanel(
                        aiService, planService, publisher, chatMemory, "test", null, true, null);
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
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel chatPanel =
                new ChatPanel(
                        aiService, planService, publisher, chatMemory, "test", null, true, null);

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
    public void testAutonomousTransitionSuppressesTurnForPlan() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        // Transition to PLAN
        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason", true));

        // Give SwingUtilities.invokeLater a moment to run
        Thread.sleep(300);

        // chat should NOT be called for PLAN
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        // Transition to REVIEW (should trigger turn)
        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason", true));
        Thread.sleep(600);

        verify(aiService, atLeastOnce()).chat(anyString(), any(), anyString(), any());
    }

    @Test
    public void testTransitionRequestedDuringWorkerAppendsAfterAiResponse() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            chatPanel.onWorkflowTransition(
                                    new WorkflowTransitionEvent(
                                            WorkflowPhase.PLAN,
                                            WorkflowPhase.CODE,
                                            "Plan ready",
                                            false));
                            return "FINAL_PLAN_SUMMARY";
                        });

        chatPanel.getInputArea().setText("Build feature");
        chatPanel.getSendButton().doClick();

        Thread.sleep(500);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        String renderedHtml = chatPanel.getOutputArea().getText();
        assertTrue(renderedHtml.contains("FINAL_PLAN_SUMMARY"), "AI response should be rendered");
        assertFalse(
                renderedHtml.contains("Roxy requested a transition"),
                "Duplicate system transition message should not be appended");

        java.lang.reflect.Field inlinePanelField =
                ChatPanel.class.getDeclaredField("inlineActionPanel");
        inlinePanelField.setAccessible(true);
        javax.swing.JPanel inlinePanel = (javax.swing.JPanel) inlinePanelField.get(chatPanel);
        assertTrue(inlinePanel.isVisible(), "Transition banner should be visible after turn");
    }

    @Test
    public void testFocusInputSetsCaretToEnd() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel chatPanel =
                new ChatPanel(
                        aiService, planService, publisher, chatMemory, "test", null, true, null);

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
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel chatPanel =
                new ChatPanel(
                        aiService, planService, publisher, chatMemory, "test", null, true, null);

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

        assertInstanceOf(
                javax.swing.border.MatteBorder.class,
                scrollPane.getBorder(),
                "outputScrollPane should use a bottom-only MatteBorder");
        assertEquals(
                new java.awt.Insets(0, 0, 1, 0),
                scrollPane.getBorder().getBorderInsets(scrollPane),
                "outputScrollPane border insets should be bottom-only (0, 0, 1, 0)");

        java.awt.Container outputWrapper = scrollPane.getParent();
        assertNotNull(outputWrapper, "outputScrollPane should have an outputWrapper parent");
        assertTrue(
                outputWrapper.getLayout() instanceof java.awt.BorderLayout,
                "outputWrapper should use BorderLayout");
        java.awt.BorderLayout layout = (java.awt.BorderLayout) outputWrapper.getLayout();
        assertNotNull(
                layout.getLayoutComponent(java.awt.BorderLayout.SOUTH),
                "inlineActionPanel should be attached at BorderLayout.SOUTH of outputWrapper in task mode");

        JSplitPane splitPane =
                (JSplitPane)
                        javax.swing.SwingUtilities.getAncestorOfClass(JSplitPane.class, scrollPane);
        assertNotNull(splitPane, "outputScrollPane should be inside JSplitPane");
        assertEquals(
                new java.awt.Insets(0, 0, 0, 0),
                splitPane.getInsets(),
                "JSplitPane should have zero outer insets so it sits flush with sidebar and tab bar");

        assertEquals(
                1.0,
                splitPane.getResizeWeight(),
                0.001,
                "splitPane resizeWeight should be 1.0 so chat output gets extra vertical space");

        javax.swing.JPanel inputSection = (javax.swing.JPanel) splitPane.getBottomComponent();
        java.awt.Container buttonPanel = chatPanel.getSendButton().getParent();
        assertFalse(
                inputSection.isPreferredSizeSet(),
                "inputSection should not have a hardcoded preferredSize that clips buttonPanel");
        assertTrue(
                inputSection.getPreferredSize().height >= buttonPanel.getPreferredSize().height,
                "inputSection preferred height should be at least as tall as buttonPanel preferred height");
        assertEquals(
                new java.awt.Dimension(0, 85),
                inputSection.getMinimumSize(),
                "inputSection should have compact minimum height of 85px");
        assertEquals(3, chatPanel.getInputArea().getRows(), "inputArea should have 3 rows");
        assertEquals(
                "Ask Roxy... (Enter to send, Shift+Enter for new line, Ctrl+V to paste image)",
                chatPanel
                        .getInputArea()
                        .getClientProperty(
                                com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT));
        assertNotNull(chatPanel.getAttachImageButton(), "attachImageButton should be initialized");
        assertEquals("Attach Image", chatPanel.getAttachImageButton().getToolTipText());

        assertInstanceOf(net.miginfocom.swing.MigLayout.class, inputSection.getLayout());
        net.miginfocom.swing.MigLayout inputLayout =
                (net.miginfocom.swing.MigLayout) inputSection.getLayout();
        assertTrue(
                inputLayout.getLayoutConstraints().toString().contains("insets 6 10 10 10"),
                "inputSection should have internal padding insets 6 10 10 10");
        assertEquals(
                "aligny top",
                inputLayout.getComponentConstraints(chatPanel.getSendButton().getParent()),
                "buttonPanel should be anchored at aligny top");
    }

    @Test
    public void testExploreAndFeatureChatPanelBeansAndWorkflowListeners() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ExploreChatPanel qaChatPanel =
                new ExploreChatPanel(aiService, planService, publisher, chatMemory, null, null);
        assertEquals("qa", qaChatPanel.getModel().getSessionPrefix());
        assertEquals(WorkflowPhase.EXPLORE, qaChatPanel.getModel().getLockedPhase());
        assertFalse(qaChatPanel.getModel().isTaskMode());

        FeatureChatPanel featureChatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);
        assertEquals("task", featureChatPanel.getModel().getSessionPrefix());
        assertNull(featureChatPanel.getModel().getLockedPhase());
        assertTrue(featureChatPanel.getModel().isTaskMode());

        // ExploreChatPanel should not declare @EventListener for workflow events
        Method[] exploreDeclared = ExploreChatPanel.class.getDeclaredMethods();
        assertFalse(
                Arrays.stream(exploreDeclared)
                        .anyMatch(m -> m.isAnnotationPresent(EventListener.class)),
                "ExploreChatPanel should not declare task workflow @EventListener methods");

        // FeatureChatPanel should declare @EventListener for WorkflowTransitionEvent,
        // PhaseChangedEvent, PlanUpdatedEvent
        assertTrue(
                FeatureChatPanel.class
                        .getDeclaredMethod("onWorkflowTransition", WorkflowTransitionEvent.class)
                        .isAnnotationPresent(EventListener.class));
        assertTrue(
                FeatureChatPanel.class
                        .getDeclaredMethod("onPhaseChanged", PhaseChangedEvent.class)
                        .isAnnotationPresent(EventListener.class));
        assertTrue(
                FeatureChatPanel.class
                        .getDeclaredMethod("onPlanUpdated", PlanUpdatedEvent.class)
                        .isAnnotationPresent(EventListener.class));
    }

    @Test
    public void testApproveCodeToPlanTriggersTurnWhileReviewToPlanCompletesTask() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        java.lang.reflect.Field approveBtnField = ChatPanel.class.getDeclaredField("approveButton");
        approveBtnField.setAccessible(true);
        javax.swing.JButton approveButton = (javax.swing.JButton) approveBtnField.get(chatPanel);

        // Case 1: CODE -> PLAN (plan revision) should trigger next turn in PLAN
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);
        doAnswer(
                        inv -> {
                            when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
                            return null;
                        })
                .when(planService)
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
                        any());
        verify(chatMemory, never()).clear(anyString());
        assertFalse(chatPanel.getOutputArea().getText().contains("Task complete"));

        // Case 2: REVIEW -> PLAN (completion) should show Task complete and not trigger
        // a new AI turn
        clearInvocations(aiService);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);

        approveButton.setEnabled(true);
        approveButton.doClick();
        Thread.sleep(300);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        verify(aiService, never()).chat(anyString(), any(), anyString(), any());
        verify(chatMemory).clear("task:default");
        assertTrue(chatPanel.getOutputArea().getText().contains("Task complete"));
    }

    @Test
    public void testSetLoadingRespectsPendingPhaseForApproveAndRejectButtons() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

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
        when(planService.getPendingPhase()).thenReturn(null);
        setLoadingMethod.invoke(chatPanel, false);
        assertFalse(
                approveButton.isEnabled(),
                "approveButton should remain disabled when setLoading(false) and no pending phase");
        assertFalse(
                rejectButton.isEnabled(),
                "rejectButton should remain disabled when setLoading(false) and no pending phase");

        // When a pending phase exists and loading=true, buttons are disabled during
        // loading
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
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
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        java.util.List<Boolean> states = new java.util.ArrayList<>();
        chatPanel.addLoadingListener(states::add);

        java.lang.reflect.Method setLoadingMethod =
                ChatPanel.class.getDeclaredMethod("setLoading", boolean.class);
        setLoadingMethod.setAccessible(true);

        setLoadingMethod.invoke(chatPanel, true);
        setLoadingMethod.invoke(chatPanel, false);

        assertEquals(java.util.List.of(true, false), states);
    }

    @Test
    public void testTurnLabelDisplaysMaxTurnsAndLivePlanStepProgress() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        org.roxycode.app.service.SettingsService settingsService =
                mock(org.roxycode.app.service.SettingsService.class);
        org.roxycode.app.model.AppSettings appSettings = new org.roxycode.app.model.AppSettings();
        appSettings.setMaxAgentToolTurns(5);
        when(settingsService.getSettings()).thenReturn(appSettings);

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(
                        aiService, planService, publisher, chatMemory, settingsService, null);

        assertEquals("Turns: 0 / 5", chatPanel.getTurnLabel().getText());

        org.roxycode.app.model.ImplementationPlan plan =
                new org.roxycode.app.model.ImplementationPlan(
                        "Feature X",
                        "Build Feature X",
                        java.util.List.of("Req 1"),
                        java.util.List.of(
                                new org.roxycode.app.model.ImplementationPlan.TechStep(
                                        "Step 0", true),
                                new org.roxycode.app.model.ImplementationPlan.TechStep(
                                        "Step 1", false),
                                new org.roxycode.app.model.ImplementationPlan.TechStep(
                                        "Step 2", false)),
                        true,
                        null,
                        null,
                        null,
                        java.util.List.of());
        when(planService.getCurrentPlan()).thenReturn(plan);

        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals("Turns: 2 / 5 | Plan: 1/3 steps", chatPanel.getTurnLabel().getText());

        // Simulate step 1 completion via PlanUpdatedEvent
        org.roxycode.app.model.ImplementationPlan updatedPlan =
                new org.roxycode.app.model.ImplementationPlan(
                        "Feature X",
                        "Build Feature X",
                        java.util.List.of("Req 1"),
                        java.util.List.of(
                                new org.roxycode.app.model.ImplementationPlan.TechStep(
                                        "Step 0", true),
                                new org.roxycode.app.model.ImplementationPlan.TechStep(
                                        "Step 1", true),
                                new org.roxycode.app.model.ImplementationPlan.TechStep(
                                        "Step 2", false)),
                        true,
                        null,
                        null,
                        null,
                        java.util.List.of());
        when(planService.getCurrentPlan()).thenReturn(updatedPlan);
        chatPanel.onPlanUpdated(new PlanUpdatedEvent(updatedPlan));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals("Turns: 2 / 5 | Plan: 2/3 steps", chatPanel.getTurnLabel().getText());
    }
}
