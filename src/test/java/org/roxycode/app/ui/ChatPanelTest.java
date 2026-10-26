package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.MatteBorder;
import net.miginfocom.swing.MigLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

public class ChatPanelTest {

    private AiService aiService;
    private PlanService planService;
    private ApplicationEventPublisher publisher;
    private ChatMemory chatMemory;
    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        planService = mock(PlanService.class);
        publisher = mock(ApplicationEventPublisher.class);
        chatMemory = mock(ChatMemory.class);
        settingsService = mock(SettingsService.class);
    }

    private ChatPanel createChatPanel() {
        return new ChatPanel(
                aiService, planService, publisher, chatMemory, "test", null, true, null, null);
    }

    private FeatureChatPanel createFeatureChatPanel(Executor executor) {
        return new FeatureChatPanel(
                aiService, planService, publisher, chatMemory, settingsService, executor);
    }

    private ExploreChatPanel createExploreChatPanel(Executor executor) {
        return new ExploreChatPanel(
                aiService, planService, publisher, chatMemory, settingsService, executor);
    }

    @Test
    public void testChatPanelInitialization() {
        ChatPanel chatPanel = createChatPanel();
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
        ChatPanel chatPanel = createChatPanel();
        assertTrue(
                chatPanel.getInputArea().getMouseListeners().length > 0,
                "Input area should have mouse listeners for context menu");
        assertTrue(
                chatPanel.getOutputArea().getMouseListeners().length > 0,
                "Output area should have mouse listeners for context menu");
    }

    @Test
    public void testSendMessageWithNullEventDoesNotThrowNPE() {
        ChatPanel chatPanel = createChatPanel();
        Action sendMessageAction = chatPanel.getInputArea().getActionMap().get("send-message");
        assertNotNull(sendMessageAction, "send-message action should exist");

        chatPanel.getInputArea().setText("Hello");
        assertDoesNotThrow(() -> sendMessageAction.actionPerformed(null));
    }

    @Test
    public void testStopButtonEnablement() {
        ChatPanel chatPanel = createChatPanel();
        JButton stopButton = chatPanel.getStopButton();
        JButton sendButton = chatPanel.getSendButton();
        JTextArea inputArea = chatPanel.getInputArea();

        assertFalse(stopButton.isEnabled(), "Stop button should be disabled initially");
        assertTrue(sendButton.isEnabled(), "Send button should be enabled initially");

        inputArea.setText("test message");
        for (ActionListener al : sendButton.getActionListeners()) {
            al.actionPerformed(new ActionEvent(sendButton, ActionEvent.ACTION_PERFORMED, null));
        }

        assertTrue(stopButton.isEnabled(), "Stop button should be enabled while sending");
        assertFalse(sendButton.isEnabled(), "Send button should be disabled while sending");
        assertFalse(inputArea.isEnabled(), "Input area should be disabled while sending");
    }

    @Test
    public void testStopButtonCancelsTurnAndBlocksLateAutonomousTransitionsAndEvents()
            throws Exception {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);

        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger chatCalls = new AtomicInteger(0);

        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            chatCalls.incrementAndGet();
                            callStarted.countDown();
                            try {
                                allowExit.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new CancellationException("Interrupted");
                            }
                            return "Late AI response";
                        });

        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        chatPanel.getInputArea().setText("Start coding");
        chatPanel.getSendButton().doClick();

        assertTrue(
                callStarted.await(5, TimeUnit.SECONDS),
                "Background worker should start aiService.chat");

        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop transition", true));
        SwingUtilities.invokeAndWait(() -> {});

        SwingUtilities.invokeAndWait(() -> chatPanel.getStopButton().doClick());
        SwingUtilities.invokeAndWait(() -> {});

        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Late transition", true));
        chatPanel.onToolExecution(
                new ToolExecutionEvent("task:default", "lateToolCall()", "ok", true, null));
        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 99, "CODE"));

        allowExit.countDown();
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> {});

        String html = chatPanel.getOutputArea().getText();
        assertTrue(
                html.contains("Request cancelled by user"),
                "Output should contain cancellation message");
        assertFalse(
                html.contains("lateToolCall"),
                "Late tool execution events after Stop must be ignored");
        assertFalse(
                html.contains("Late transition"),
                "Late autonomous transition events after Stop must be ignored");
        assertEquals(
                1,
                chatCalls.get(),
                "Cancelled turn must not auto-trigger a follow-up turn from queued or late transitions");
    }

    @Test
    public void testAutonomousTransitionSuppressesTurnForPlan() throws Exception {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);

        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason", true));
        Thread.sleep(300);

        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason", true));
        Thread.sleep(600);

        verify(aiService, atLeastOnce()).chat(anyString(), any(), anyString(), any());
    }

    @Test
    public void testTransitionRequestedDuringWorkerAppendsAfterAiResponse() throws Exception {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);

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
        SwingUtilities.invokeAndWait(() -> {});

        String renderedHtml = chatPanel.getOutputArea().getText();
        assertTrue(renderedHtml.contains("FINAL_PLAN_SUMMARY"), "AI response should be rendered");
        assertFalse(
                renderedHtml.contains("Roxy requested a transition"),
                "Duplicate system transition message should not be appended");

        assertTrue(
                chatPanel.getInlineActionPanel().isVisible(),
                "Transition banner should be visible after turn");
        assertEquals("Approve Plan and proceed to Code", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.REVIEW, WorkflowPhase.PLAN, "All done", false));
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(
                "Approve Feature Request as complete", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.PLAN, "Need revision", false));
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(
                "Approve Plan Revision and return to Plan",
                chatPanel.getTransitionLabel().getText());
    }

    @Test
    public void testFocusInputSetsCaretToEnd() {
        ChatPanel chatPanel = createChatPanel();

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
        ChatPanel chatPanel = createChatPanel();

        assertTrue(
                chatPanel.getOutputArea().getScrollableTracksViewportWidth(),
                "MarkdownPane should track viewport width");

        JScrollPane scrollPane =
                (JScrollPane)
                        SwingUtilities.getAncestorOfClass(
                                JScrollPane.class, chatPanel.getOutputArea());
        assertNotNull(scrollPane, "Output area should be inside a JScrollPane");
        assertSame(
                chatPanel.getOutputArea(),
                scrollPane.getViewport().getView(),
                "Output area should be the direct viewport view of outputScrollPane");
        assertEquals(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scrollPane.getHorizontalScrollBarPolicy(),
                "Horizontal scrollbar policy should be HORIZONTAL_SCROLLBAR_NEVER");

        assertInstanceOf(
                MatteBorder.class,
                scrollPane.getBorder(),
                "outputScrollPane should use a bottom-only MatteBorder");
        assertEquals(
                new Insets(0, 0, 1, 0),
                scrollPane.getBorder().getBorderInsets(scrollPane),
                "outputScrollPane border insets should be bottom-only (0, 0, 1, 0)");

        Container outputWrapper = scrollPane.getParent();
        assertNotNull(outputWrapper, "outputScrollPane should have an outputWrapper parent");
        assertTrue(
                outputWrapper.getLayout() instanceof BorderLayout,
                "outputWrapper should use BorderLayout");
        BorderLayout layout = (BorderLayout) outputWrapper.getLayout();
        assertNotNull(
                layout.getLayoutComponent(BorderLayout.SOUTH),
                "inlineActionPanel should be attached at BorderLayout.SOUTH of outputWrapper in task mode");

        JSplitPane splitPane =
                (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, scrollPane);
        assertNotNull(splitPane, "outputScrollPane should be inside JSplitPane");
        assertEquals(
                new Insets(0, 0, 0, 0),
                splitPane.getInsets(),
                "JSplitPane should have zero outer insets so it sits flush with sidebar and tab bar");

        assertEquals(
                1.0,
                splitPane.getResizeWeight(),
                0.001,
                "splitPane resizeWeight should be 1.0 so chat output gets extra vertical space");

        JPanel inputSection = (JPanel) splitPane.getBottomComponent();
        Container buttonPanel = chatPanel.getSendButton().getParent();
        assertFalse(
                inputSection.isPreferredSizeSet(),
                "inputSection should not have a hardcoded preferredSize that clips buttonPanel");
        assertTrue(
                inputSection.getPreferredSize().height >= buttonPanel.getPreferredSize().height,
                "inputSection preferred height should be at least as tall as buttonPanel preferred height");
        assertEquals(
                new Dimension(0, 85),
                inputSection.getMinimumSize(),
                "inputSection should have compact minimum height of 85px");
        assertEquals(3, chatPanel.getInputArea().getRows(), "inputArea should have 3 rows");
        assertEquals(
                "Ask Roxy... (Enter to send, Shift+Enter for new line, Ctrl+V to paste image)",
                chatPanel.getInputArea().getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertNotNull(chatPanel.getAttachImageButton(), "attachImageButton should be initialized");
        assertEquals("Attach Image", chatPanel.getAttachImageButton().getToolTipText());

        assertInstanceOf(MigLayout.class, inputSection.getLayout());
        MigLayout inputLayout = (MigLayout) inputSection.getLayout();
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
        ExploreChatPanel qaChatPanel = createExploreChatPanel(null);
        assertEquals("qa", qaChatPanel.getModel().getSessionPrefix());
        assertEquals(WorkflowPhase.EXPLORE, qaChatPanel.getModel().getLockedPhase());
        assertFalse(qaChatPanel.getModel().isTaskMode());

        FeatureChatPanel featureChatPanel = createFeatureChatPanel(null);
        assertEquals("task", featureChatPanel.getModel().getSessionPrefix());
        assertNull(featureChatPanel.getModel().getLockedPhase());
        assertTrue(featureChatPanel.getModel().isTaskMode());

        Method[] exploreDeclared = ExploreChatPanel.class.getDeclaredMethods();
        assertFalse(
                Arrays.stream(exploreDeclared)
                        .anyMatch(m -> m.isAnnotationPresent(EventListener.class)),
                "ExploreChatPanel should not declare task workflow @EventListener methods");

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
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        JButton approveButton = chatPanel.getApproveButton();

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
        SwingUtilities.invokeAndWait(() -> {});

        verify(aiService, atLeastOnce())
                .chat(
                        eq("task:default"),
                        eq(WorkflowPhase.PLAN),
                        contains("I approve the transition to Plan"),
                        any());
        verify(chatMemory, never()).clear(anyString());
        assertFalse(chatPanel.getOutputArea().getText().contains("Task complete"));

        clearInvocations(aiService);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);

        approveButton.setEnabled(true);
        approveButton.doClick();
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {});

        verify(aiService, never()).chat(anyString(), any(), anyString(), any());
        verify(chatMemory).clear("task:default");
        assertTrue(chatPanel.getOutputArea().getText().contains("Task complete"));
    }

    @Test
    public void testSetLoadingRespectsPendingPhaseForApproveAndRejectButtons() {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        JButton approveButton = chatPanel.getApproveButton();
        JButton rejectButton = chatPanel.getRejectButton();

        assertFalse(approveButton.isEnabled(), "approveButton should be disabled initially");
        assertFalse(rejectButton.isEnabled(), "rejectButton should be disabled initially");

        when(planService.getPendingPhase()).thenReturn(null);
        chatPanel.setLoading(false);
        assertFalse(
                approveButton.isEnabled(),
                "approveButton should remain disabled when setLoading(false) and no pending phase");
        assertFalse(
                rejectButton.isEnabled(),
                "rejectButton should remain disabled when setLoading(false) and no pending phase");

        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        chatPanel.setLoading(true);
        assertFalse(approveButton.isEnabled(), "approveButton should be disabled during loading");
        assertFalse(rejectButton.isEnabled(), "rejectButton should be disabled during loading");

        chatPanel.setLoading(false);
        assertTrue(
                approveButton.isEnabled(),
                "approveButton should be enabled when setLoading(false) and pending phase exists");
        assertTrue(
                rejectButton.isEnabled(),
                "rejectButton should be enabled when setLoading(false) and pending phase exists");
    }

    @Test
    public void testLoadingListenerNotified() {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);

        List<Boolean> states = new ArrayList<>();
        chatPanel.addLoadingListener(states::add);

        chatPanel.setLoading(true);
        chatPanel.setLoading(false);

        assertEquals(List.of(true, false), states);
    }

    @Test
    public void testTurnLabelDisplaysMaxTurnsAndLivePlanStepProgress() throws Exception {
        AppSettings appSettings = new AppSettings();
        appSettings.setMaxAgentToolTurns(5);
        when(settingsService.getSettings()).thenReturn(appSettings);

        FeatureChatPanel chatPanel = createFeatureChatPanel(null);

        assertEquals("Turns: 0 / 5", chatPanel.getTurnLabel().getText());

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Feature X",
                        "Build Feature X",
                        List.of("Req 1"),
                        List.of(
                                new ImplementationPlan.TechStep("Step 0", true),
                                new ImplementationPlan.TechStep("Step 1", false),
                                new ImplementationPlan.TechStep("Step 2", false)),
                        true,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(plan);

        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals("Turns: 2 / 5 | Plan: 1/3 steps", chatPanel.getTurnLabel().getText());

        ImplementationPlan updatedPlan =
                new ImplementationPlan(
                        "Feature X",
                        "Build Feature X",
                        List.of("Req 1"),
                        List.of(
                                new ImplementationPlan.TechStep("Step 0", true),
                                new ImplementationPlan.TechStep("Step 1", true),
                                new ImplementationPlan.TechStep("Step 2", false)),
                        true,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(updatedPlan);
        chatPanel.onPlanUpdated(new PlanUpdatedEvent(updatedPlan));
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals("Turns: 2 / 5 | Plan: 2/3 steps", chatPanel.getTurnLabel().getText());
    }
}
