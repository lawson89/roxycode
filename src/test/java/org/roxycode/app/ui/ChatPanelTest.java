package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
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
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.springframework.context.event.EventListener;

public class ChatPanelTest extends ChatTestFixture {

    @Test
    public void testChatPanelInitialization() {
        ChatPanel chatPanel = createChatPanel();
        assertNotNull(chatPanel.getOutputArea());
        assertInstanceOf(JTextArea.class, chatPanel.getInputArea());
        assertNotNull(chatPanel.getSendButton());
        assertNotNull(chatPanel.getStopButton());
        assertEquals("Send", chatPanel.getSendButton().getText());
        assertFalse(chatPanel.getOutputArea().isEditable());
        assertTrue(Arrays.stream(chatPanel.getComponents()).anyMatch(JSplitPane.class::isInstance));
    }

    @Test
    public void testContextMenuListeners() {
        ChatPanel chatPanel = createChatPanel();
        assertTrue(chatPanel.getInputArea().getMouseListeners().length > 0);
        assertTrue(chatPanel.getOutputArea().getMouseListeners().length > 0);
    }

    @Test
    public void testSendMessageWithNullEventDoesNotThrowNPE() {
        ChatPanel chatPanel = createChatPanel();
        Action sendMessageAction = chatPanel.getInputArea().getActionMap().get("send-message");
        assertNotNull(sendMessageAction);
        chatPanel.getInputArea().setText("Hello");
        assertDoesNotThrow(() -> sendMessageAction.actionPerformed(null));
    }

    @Test
    public void testStopButtonEnablement() {
        ChatPanel chatPanel = createChatPanel();
        JButton stopButton = chatPanel.getStopButton();
        JButton sendButton = chatPanel.getSendButton();
        JTextArea inputArea = chatPanel.getInputArea();

        assertFalse(stopButton.isEnabled());
        assertTrue(sendButton.isEnabled());

        inputArea.setText("test message");
        for (ActionListener al : sendButton.getActionListeners()) {
            al.actionPerformed(new ActionEvent(sendButton, ActionEvent.ACTION_PERFORMED, null));
        }

        assertTrue(stopButton.isEnabled());
        assertFalse(sendButton.isEnabled());
        assertFalse(inputArea.isEnabled());
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

        assertTrue(callStarted.await(5, TimeUnit.SECONDS));

        chatPanel.onWorkflowTransition(
                transitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop transition", true));
        flushEdt();
        SwingTestUtils.clickOnEdt(chatPanel.getStopButton());
        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Late transition", true));
        chatPanel.onToolExecution(
                new ToolExecutionEvent("task:default", "lateToolCall()", "ok", true, null));
        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 99, "CODE"));

        allowExit.countDown();
        Thread.sleep(200);
        flushEdt();

        String html = chatPanel.getOutputArea().getText();
        assertTrue(html.contains("Request cancelled by user"));
        assertFalse(html.contains("lateToolCall"));
        assertFalse(html.contains("Late transition"));
        assertEquals(1, chatCalls.get());
    }

    @Test
    public void testAutonomousTransitionSuppressesTurnForPlan() throws Exception {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);

        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason", true));
        Thread.sleep(300);
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason", true));
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
                                    transitionEvent(
                                            WorkflowPhase.PLAN,
                                            WorkflowPhase.CODE,
                                            "Plan ready",
                                            false));
                            return "FINAL_PLAN_SUMMARY";
                        });

        chatPanel.getInputArea().setText("Build feature");
        chatPanel.getSendButton().doClick();

        Thread.sleep(500);
        flushEdt();

        String renderedHtml = chatPanel.getOutputArea().getText();
        assertTrue(renderedHtml.contains("FINAL_PLAN_SUMMARY"));
        assertFalse(renderedHtml.contains("Roxy requested a transition"));
        assertTrue(chatPanel.getInlineActionPanel().isVisible());
        assertEquals("Approve Plan and proceed to Code", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.REVIEW, WorkflowPhase.PLAN, "All done", false));
        flushEdt();
        assertEquals(
                "Approve Feature Request as complete", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Need revision", false));
        flushEdt();
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
                "Existing text in input".length(), chatPanel.getInputArea().getCaretPosition());
    }

    @Test
    public void testOutputScrollPaneAndWrappingConfiguration() {
        ChatPanel chatPanel = createChatPanel();
        assertTrue(chatPanel.getOutputArea().getScrollableTracksViewportWidth());

        JScrollPane scrollPane =
                (JScrollPane)
                        SwingUtilities.getAncestorOfClass(
                                JScrollPane.class, chatPanel.getOutputArea());
        assertNotNull(scrollPane);
        assertSame(chatPanel.getOutputArea(), scrollPane.getViewport().getView());
        assertEquals(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scrollPane.getHorizontalScrollBarPolicy());
        assertInstanceOf(MatteBorder.class, scrollPane.getBorder());
        assertEquals(new Insets(0, 0, 1, 0), scrollPane.getBorder().getBorderInsets(scrollPane));

        Container outputWrapper = scrollPane.getParent();
        assertNotNull(outputWrapper);
        BorderLayout layout = assertInstanceOf(BorderLayout.class, outputWrapper.getLayout());
        assertNotNull(layout.getLayoutComponent(BorderLayout.SOUTH));

        JSplitPane splitPane =
                (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, scrollPane);
        assertNotNull(splitPane);
        assertEquals(new Insets(0, 0, 0, 0), splitPane.getInsets());
        assertEquals(1.0, splitPane.getResizeWeight(), 0.001);

        JPanel inputSection = (JPanel) splitPane.getBottomComponent();
        Container buttonPanel = chatPanel.getSendButton().getParent();
        assertFalse(inputSection.isPreferredSizeSet());
        assertTrue(inputSection.getPreferredSize().height >= buttonPanel.getPreferredSize().height);
        assertEquals(new Dimension(0, 85), inputSection.getMinimumSize());
        assertEquals(3, chatPanel.getInputArea().getRows());
        assertEquals(
                "Ask Roxy... (Enter to send, Shift+Enter for new line, Ctrl+V to paste image)",
                chatPanel.getInputArea().getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertNotNull(chatPanel.getAttachImageButton());
        assertEquals("Attach Image", chatPanel.getAttachImageButton().getToolTipText());

        MigLayout inputLayout = assertInstanceOf(MigLayout.class, inputSection.getLayout());
        assertTrue(inputLayout.getLayoutConstraints().toString().contains("insets 6 10 10 10"));
        assertEquals("aligny top", inputLayout.getComponentConstraints(buttonPanel));
    }

    @Test
    public void testExploreAndFeatureChatPanelBeansAndWorkflowListeners() throws Exception {
        ExploreChatPanel qaChatPanel = createExploreChatPanel(null);
        assertEquals("qa", qaChatPanel.getModel().getSessionPrefix());
        assertEquals(WorkflowPhase.EXPLORE, qaChatPanel.getModel().getLockedPhase());
        assertFalse(qaChatPanel.getModel().isTaskMode());
        assertEquals("New Topic", qaChatPanel.getResetSessionButton().getText());
        assertEquals(
                ChatPanel.NEW_TOPIC_TOOLTIP, qaChatPanel.getResetSessionButton().getToolTipText());

        FeatureChatPanel featureChatPanel = createFeatureChatPanel(null);
        assertEquals("task", featureChatPanel.getModel().getSessionPrefix());
        assertNull(featureChatPanel.getModel().getLockedPhase());
        assertTrue(featureChatPanel.getModel().isTaskMode());
        assertEquals("Reset Session", featureChatPanel.getResetSessionButton().getText());

        Method[] exploreDeclared = ExploreChatPanel.class.getDeclaredMethods();
        assertFalse(
                Arrays.stream(exploreDeclared)
                        .anyMatch(m -> m.isAnnotationPresent(EventListener.class)));

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
        flushEdt();

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
        flushEdt();

        verify(aiService, never()).chat(anyString(), any(), anyString(), any());
        verify(chatMemory).clear("task:default");
        assertTrue(chatPanel.getOutputArea().getText().contains("Task complete"));
    }

    @Test
    public void testSetLoadingRespectsPendingPhaseForApproveAndRejectButtons() {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        JButton approveButton = chatPanel.getApproveButton();
        JButton rejectButton = chatPanel.getRejectButton();

        assertFalse(approveButton.isEnabled());
        assertFalse(rejectButton.isEnabled());

        when(planService.getPendingPhase()).thenReturn(null);
        chatPanel.setLoading(false);
        assertFalse(approveButton.isEnabled());
        assertFalse(rejectButton.isEnabled());

        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        chatPanel.setLoading(true);
        assertFalse(approveButton.isEnabled());
        assertFalse(rejectButton.isEnabled());

        chatPanel.setLoading(false);
        assertTrue(approveButton.isEnabled());
        assertTrue(rejectButton.isEnabled());
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
                samplePlan(
                        new ImplementationPlan.TechStep("Step 0", true),
                        new ImplementationPlan.TechStep("Step 1", false),
                        new ImplementationPlan.TechStep("Step 2", false));
        when(planService.getCurrentPlan()).thenReturn(plan);

        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 1/3 steps", chatPanel.getTurnLabel().getText());

        ImplementationPlan updatedPlan =
                samplePlan(
                        new ImplementationPlan.TechStep("Step 0", true),
                        new ImplementationPlan.TechStep("Step 1", true),
                        new ImplementationPlan.TechStep("Step 2", false));
        when(planService.getCurrentPlan()).thenReturn(updatedPlan);
        chatPanel.onPlanUpdated(new PlanUpdatedEvent(updatedPlan));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 2/3 steps", chatPanel.getTurnLabel().getText());
    }

    @Test
    public void testLoadImageAttachmentAsyncAndBilinearThumbnailCreation(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ChatPanel chatPanel = createChatPanel();
        java.awt.image.BufferedImage src =
                new java.awt.image.BufferedImage(
                        120, 80, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.image.BufferedImage thumb =
                ChatPanel.createThumbnail(src, ChatPanel.THUMBNAIL_SIZE);
        assertEquals(64, thumb.getWidth());
        assertEquals(64, thumb.getHeight());

        java.io.File imgFile = tempDir.resolve("sample.png").toFile();
        javax.imageio.ImageIO.write(src, "png", imgFile);

        java.awt.image.BufferedImage loaded =
                SwingTestUtils.awaitWorker(chatPanel.loadImageAttachmentAsync(imgFile));

        assertNotNull(loaded);
        assertEquals(120, loaded.getWidth());
        assertTrue(chatPanel.getModel().hasPendingImages());
        assertEquals(1, chatPanel.getModel().getPendingImages().size());
    }

    @Test
    public void testFileMentionAutocompleteKeyboardNavigationAndEnterInterception()
            throws Exception {
        ChatPanel chatPanel = createChatPanel();
        org.roxycode.app.ai.services.GrepService grepService =
                mock(org.roxycode.app.ai.services.GrepService.class);
        when(grepService.listFiles())
                .thenReturn(
                        List.of(
                                "src/main/java/org/roxycode/app/ui/ChatPanel.java",
                                "src/main/java/org/roxycode/app/ui/ChatController.java",
                                "src/main/java/org/roxycode/app/ui/ChatModel.java"));

        FileMentionPopup mentionPopup = chatPanel.getFileMentionPopup();
        mentionPopup.setBackgroundExecutor(Runnable::run);
        chatPanel.setGrepService(grepService);

        JTextArea inputArea = chatPanel.getInputArea();
        SwingTestUtils.runOnEdt(
                () -> {
                    inputArea.setText("Review @Chat");
                    inputArea.setCaretPosition(inputArea.getText().length());
                });
        flushEdt();

        assertTrue(mentionPopup.isPopupVisible());
        assertEquals(3, mentionPopup.getListModel().size());
        assertEquals(0, mentionPopup.getCandidateList().getSelectedIndex());
        assertEquals(
                "src/main/java/org/roxycode/app/ui/ChatModel.java",
                mentionPopup.getListModel().get(0).relativePath());

        Action downAction = inputArea.getActionMap().get("autocomplete-or-down");
        Action upAction = inputArea.getActionMap().get("autocomplete-or-up");
        Action enterAction = inputArea.getActionMap().get("send-message");
        Action tabAction = inputArea.getActionMap().get("autocomplete-or-tab");
        Action escapeAction = inputArea.getActionMap().get("dismiss-autocomplete");

        // Navigate DOWN to index 1 (ChatPanel.java)
        SwingTestUtils.runOnEdt(() -> downAction.actionPerformed(null));
        assertEquals(1, mentionPopup.getCandidateList().getSelectedIndex());
        assertEquals(
                "src/main/java/org/roxycode/app/ui/ChatPanel.java",
                mentionPopup.getCandidateList().getSelectedValue().relativePath());

        // Navigate UP back to index 0, then UP again to wrap around to index 2
        SwingTestUtils.runOnEdt(() -> upAction.actionPerformed(null));
        assertEquals(0, mentionPopup.getCandidateList().getSelectedIndex());
        SwingTestUtils.runOnEdt(() -> upAction.actionPerformed(null));
        assertEquals(2, mentionPopup.getCandidateList().getSelectedIndex());

        // Press ENTER while popup is visible: must insert mention + trailing space and NOT send
        // message
        SwingTestUtils.runOnEdt(() -> enterAction.actionPerformed(null));
        flushEdt();

        assertFalse(mentionPopup.isPopupVisible());
        assertEquals(
                "Review @src/main/java/org/roxycode/app/ui/ChatController.java ",
                inputArea.getText());
        assertEquals(inputArea.getText().length(), inputArea.getCaretPosition());
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        // Type another @ mention and accept with TAB
        SwingTestUtils.runOnEdt(
                () -> {
                    inputArea.insert("@ChatM", inputArea.getCaretPosition());
                });
        flushEdt();
        assertTrue(mentionPopup.isPopupVisible());

        SwingTestUtils.runOnEdt(() -> tabAction.actionPerformed(null));
        flushEdt();
        assertFalse(mentionPopup.isPopupVisible());
        assertEquals(
                "Review @src/main/java/org/roxycode/app/ui/ChatController.java "
                        + "@src/main/java/org/roxycode/app/ui/ChatModel.java ",
                inputArea.getText());

        // Type another @ mention and dismiss with ESCAPE
        SwingTestUtils.runOnEdt(
                () -> {
                    inputArea.insert("@ChatP", inputArea.getCaretPosition());
                });
        flushEdt();
        assertTrue(mentionPopup.isPopupVisible());

        SwingTestUtils.runOnEdt(() -> escapeAction.actionPerformed(null));
        assertFalse(mentionPopup.isPopupVisible());
        assertTrue(inputArea.getText().endsWith("@ChatP"));

        // Subsequent ENTER when popup is hidden sends the message as normal
        SwingTestUtils.runOnEdt(() -> enterAction.actionPerformed(null));
        flushEdt();
        assertEquals("", inputArea.getText());
    }

    @Test
    public void testFileMentionCacheInvalidatedOnProjectAndFileEvents() {
        ChatPanel chatPanel = createChatPanel();
        org.roxycode.app.ai.services.GrepService grepService =
                mock(org.roxycode.app.ai.services.GrepService.class);
        when(grepService.listFiles()).thenReturn(List.of("src/App.java"));
        chatPanel.getFileMentionPopup().setBackgroundExecutor(Runnable::run);
        chatPanel.setGrepService(grepService);
        verify(grepService, times(1)).listFiles();

        chatPanel.onProjectChanged(
                new org.roxycode.app.events.ProjectChangedEvent(java.nio.file.Path.of(".")));
        verify(grepService, times(2)).listFiles();

        chatPanel.onFileChanged(new org.roxycode.app.events.FileChangedEvent("src/NewFile.java"));
        verify(grepService, times(3)).listFiles();
    }
}
