package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.MatteBorder;
import net.miginfocom.swing.MigLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.*;
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
    public void testStopButtonCancelsTurnAndBlocksLateAutonomousTransitionsAndEvents() throws Exception {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger chatCalls = new AtomicInteger(0);

        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
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

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop", true));
        flushEdt();
        SwingTestUtils.clickOnEdt(chatPanel.getStopButton());
        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Late transition", true));
        chatPanel.onToolExecution(new ToolExecutionEvent("task:default", "lateToolCall()", "ok", true, null));
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
        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason", true));
        Thread.sleep(300);
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason", true));
        Thread.sleep(600);
        verify(aiService, atLeastOnce()).chat(anyString(), any(), anyString(), any());
    }

    @Test
    public void testTransitionRequestedDuringWorkerAppendsAfterAiResponse() throws Exception {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready", false));
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

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.REVIEW, WorkflowPhase.PLAN, "All done", false));
        flushEdt();
        assertEquals("Approve Feature Request as complete", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Need revision", false));
        flushEdt();
        assertEquals("Approve Plan Revision and return to Plan", chatPanel.getTransitionLabel().getText());
    }

    @Test
    public void testFocusInputSetsCaretToEnd() {
        ChatPanel chatPanel = createChatPanel();
        chatPanel.getInputArea().setText("Existing text in input");
        chatPanel.getInputArea().setCaretPosition(0);
        assertEquals(0, chatPanel.getInputArea().getCaretPosition());

        chatPanel.focusInput();
        assertEquals("Existing text in input".length(), chatPanel.getInputArea().getCaretPosition());
    }

    @Test
    public void testOutputScrollPaneAndWrappingConfiguration() {
        ChatPanel chatPanel = createChatPanel();
        assertTrue(chatPanel.getOutputArea().getScrollableTracksViewportWidth());

        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class,
                chatPanel.getOutputArea());
        assertNotNull(scrollPane);
        assertSame(chatPanel.getOutputArea(), scrollPane.getViewport().getView());
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.getHorizontalScrollBarPolicy());
        assertInstanceOf(MatteBorder.class, scrollPane.getBorder());
        assertEquals(new Insets(0, 0, 1, 0), scrollPane.getBorder().getBorderInsets(scrollPane));

        Container outputWrapper = scrollPane.getParent();
        BorderLayout layout = assertInstanceOf(BorderLayout.class, outputWrapper.getLayout());
        assertNotNull(layout.getLayoutComponent(BorderLayout.SOUTH));

        JSplitPane splitPane = (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, scrollPane);
        assertEquals(new Insets(0, 0, 0, 0), splitPane.getInsets());
        assertEquals(1.0, splitPane.getResizeWeight(), 0.001);

        JPanel inputSection = (JPanel) splitPane.getBottomComponent();
        Container buttonPanel = chatPanel.getSendButton().getParent();
        assertFalse(inputSection.isPreferredSizeSet());
        assertTrue(inputSection.getPreferredSize().height >= buttonPanel.getPreferredSize().height);
        assertEquals(new Dimension(0, 85), inputSection.getMinimumSize());
        assertEquals(3, chatPanel.getInputArea().getRows());
        assertEquals("Ask Roxy... (Enter to send, Shift+Enter for new line, Ctrl+V to paste image)",
                chatPanel.getInputArea().getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
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
        assertEquals(ChatPanel.NEW_TOPIC_TOOLTIP, qaChatPanel.getResetSessionButton().getToolTipText());

        FeatureChatPanel featureChatPanel = createFeatureChatPanel(null);
        assertEquals("task", featureChatPanel.getModel().getSessionPrefix());
        assertNull(featureChatPanel.getModel().getLockedPhase());
        assertTrue(featureChatPanel.getModel().isTaskMode());
        assertEquals("Reset Session", featureChatPanel.getResetSessionButton().getText());

        assertFalse(Arrays.stream(ExploreChatPanel.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(EventListener.class)));
        for (var m : List.of(
                FeatureChatPanel.class.getDeclaredMethod("onWorkflowTransition", WorkflowTransitionEvent.class),
                FeatureChatPanel.class.getDeclaredMethod("onPhaseChanged", PhaseChangedEvent.class),
                FeatureChatPanel.class.getDeclaredMethod("onPlanUpdated", PlanUpdatedEvent.class))) {
            assertTrue(m.isAnnotationPresent(EventListener.class));
        }
    }

    @Test
    public void testApproveCodeToPlanTriggersTurnWhileReviewToPlanCompletesTask() throws Exception {
        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        JButton approveButton = chatPanel.getApproveButton();

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);
        doAnswer(inv -> {
            when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
            return null;
        }).when(planService).approveTransition();

        approveButton.setEnabled(true);
        approveButton.doClick();
        Thread.sleep(400);
        flushEdt();

        verify(aiService, atLeastOnce()).chat(eq("task:default"), eq(WorkflowPhase.PLAN),
                contains("I approve the transition to Plan"), any());
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
    public void testTurnLabelDisplaysMaxTurnsAndLivePlanStepProgress() {
        AppSettings appSettings = new AppSettings();
        appSettings.setMaxAgentToolTurns(5);
        when(settingsService.getSettings()).thenReturn(appSettings);

        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        assertEquals("Turns: 0 / 5", chatPanel.getTurnLabel().getText());

        ImplementationPlan plan = samplePlan(new ImplementationPlan.TechStep("Step 0", true),
                new ImplementationPlan.TechStep("Step 1", false), new ImplementationPlan.TechStep("Step 2", false));
        when(planService.getCurrentPlan()).thenReturn(plan);

        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 1/3 steps", chatPanel.getTurnLabel().getText());

        ImplementationPlan updatedPlan = samplePlan(new ImplementationPlan.TechStep("Step 0", true),
                new ImplementationPlan.TechStep("Step 1", true), new ImplementationPlan.TechStep("Step 2", false));
        when(planService.getCurrentPlan()).thenReturn(updatedPlan);
        chatPanel.onPlanUpdated(new PlanUpdatedEvent(updatedPlan));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 2/3 steps", chatPanel.getTurnLabel().getText());
    }

    @Test
    public void testLoadImageAttachmentAsyncAndBilinearThumbnailCreation(@TempDir Path tempDir) throws Exception {
        ChatPanel chatPanel = createChatPanel();
        BufferedImage src = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        BufferedImage thumb = ChatPanel.createThumbnail(src, ChatPanel.THUMBNAIL_SIZE);
        assertEquals(64, thumb.getWidth());
        assertEquals(64, thumb.getHeight());

        File imgFile = tempDir.resolve("sample.png").toFile();
        ImageIO.write(src, "png", imgFile);

        BufferedImage loaded = SwingTestUtils.awaitWorker(chatPanel.loadImageAttachmentAsync(imgFile));
        assertNotNull(loaded);
        assertEquals(120, loaded.getWidth());
        assertTrue(chatPanel.getModel().hasPendingImages());
        assertEquals(1, chatPanel.getModel().getPendingImages().size());
    }

    @Test
    public void testFileMentionAutocompleteKeyboardNavigationAndEnterInterception() {
        ChatPanel chatPanel = createChatPanel();
        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles()).thenReturn(List.of("src/main/java/org/roxycode/app/ui/ChatPanel.java",
                "src/main/java/org/roxycode/app/ui/ChatController.java",
                "src/main/java/org/roxycode/app/ui/ChatModel.java"));

        FileMentionPopup mentionPopup = chatPanel.getFileMentionPopup();
        mentionPopup.setBackgroundExecutor(Runnable::run);
        chatPanel.setGrepService(grepService);

        JTextArea inputArea = chatPanel.getInputArea();
        SwingTestUtils.runOnEdt(() -> {
            inputArea.setText("Review @Chat");
            inputArea.setCaretPosition(inputArea.getText().length());
        });
        flushEdt();

        assertTrue(mentionPopup.isPopupVisible());
        assertEquals(3, mentionPopup.getListModel().size());
        assertEquals(0, mentionPopup.getCandidateList().getSelectedIndex());
        assertEquals("src/main/java/org/roxycode/app/ui/ChatModel.java",
                mentionPopup.getListModel().get(0).relativePath());

        ActionMap am = inputArea.getActionMap();
        Action downAction = am.get("autocomplete-or-down");
        Action upAction = am.get("autocomplete-or-up");
        Action enterAction = am.get("send-message");
        Action tabAction = am.get("autocomplete-or-tab");
        Action escapeAction = am.get("dismiss-autocomplete");

        SwingTestUtils.runOnEdt(() -> downAction.actionPerformed(null));
        assertEquals(1, mentionPopup.getCandidateList().getSelectedIndex());
        assertEquals("src/main/java/org/roxycode/app/ui/ChatPanel.java",
                mentionPopup.getCandidateList().getSelectedValue().relativePath());

        SwingTestUtils.runOnEdt(() -> upAction.actionPerformed(null));
        assertEquals(0, mentionPopup.getCandidateList().getSelectedIndex());
        SwingTestUtils.runOnEdt(() -> upAction.actionPerformed(null));
        assertEquals(2, mentionPopup.getCandidateList().getSelectedIndex());

        SwingTestUtils.runOnEdt(() -> enterAction.actionPerformed(null));
        flushEdt();
        assertFalse(mentionPopup.isPopupVisible());
        assertEquals("Review @src/main/java/org/roxycode/app/ui/ChatController.java ", inputArea.getText());
        assertEquals(inputArea.getText().length(), inputArea.getCaretPosition());
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        SwingTestUtils.runOnEdt(() -> inputArea.insert("@ChatM", inputArea.getCaretPosition()));
        flushEdt();
        assertTrue(mentionPopup.isPopupVisible());

        SwingTestUtils.runOnEdt(() -> tabAction.actionPerformed(null));
        flushEdt();
        assertFalse(mentionPopup.isPopupVisible());
        assertEquals("Review @src/main/java/org/roxycode/app/ui/ChatController.java "
                + "@src/main/java/org/roxycode/app/ui/ChatModel.java ", inputArea.getText());

        SwingTestUtils.runOnEdt(() -> inputArea.insert("@ChatP", inputArea.getCaretPosition()));
        flushEdt();
        assertTrue(mentionPopup.isPopupVisible());

        SwingTestUtils.runOnEdt(() -> escapeAction.actionPerformed(null));
        assertFalse(mentionPopup.isPopupVisible());
        assertTrue(inputArea.getText().endsWith("@ChatP"));

        SwingTestUtils.runOnEdt(() -> enterAction.actionPerformed(null));
        flushEdt();
        assertEquals("", inputArea.getText());
    }

    @Test
    public void testFileMentionCacheInvalidatedOnProjectAndFileEvents() {
        ChatPanel chatPanel = createChatPanel();
        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles()).thenReturn(List.of("src/App.java"));
        chatPanel.getFileMentionPopup().setBackgroundExecutor(Runnable::run);
        chatPanel.setGrepService(grepService);
        verify(grepService, times(1)).listFiles();

        chatPanel.onProjectChanged(new ProjectChangedEvent(Path.of(".")));
        verify(grepService, times(2)).listFiles();

        chatPanel.onFileChanged(new FileChangedEvent("src/NewFile.java"));
        verify(grepService, times(3)).listFiles();
    }
}
