package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.*;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;

class ChatControllerTest extends ChatTestFixture {

    @Test
    void testRejectPhaseNotifiesWorkflowAndTriggersTurn() {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenReturn("Let's revise the plan.");

        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);
        assertSame(directExecutor, panel.getController().getTurnExecutor());

        panel.getController().rejectPhase();
        flushEdt();

        verify(planService).rejectTransition();
        verify(aiService).chat(eq("task:default"), eq(WorkflowPhase.PLAN),
                contains("I have rejected the transition to Code"), any());
        assertEquals(0L, panel.getController().getActiveTurnId());
        assertTrue(panel.getOutputArea().getText().contains("Transition to"));
    }

    @Test
    void testPerformResetSessionAndResetForProjectChangeBehavior() {
        FeatureChatPanel panel = createFeatureChatPanel(null);
        panel.getModel().setCurrentTurn(3);
        panel.getController().performResetSession();
        flushEdt();

        verify(planService).resetWorkflow();
        verify(chatMemory).clear("task:default");
        assertEquals(0, panel.getModel().getCurrentTurn());
        assertEquals(0L, panel.getController().getActiveTurnId());
        assertTrue(panel.getOutputArea().getText().contains("Session reset."));

        clearInvocations(planService, chatMemory);
        panel.appendMessage("user", "Project-specific message");
        panel.getModel().setCurrentTurn(2);
        panel.getModel().addPendingImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));

        panel.getController().resetForProjectChange();
        flushEdt();

        verify(planService).resetWorkflow();
        verify(chatMemory, never()).clear(anyString());
        assertEquals(0, panel.getModel().getCurrentTurn());
        assertFalse(panel.getModel().hasPendingImages());
        assertTrue(panel.getConversationMessages().isEmpty());
        assertFalse(panel.getOutputArea().getText().contains("Session reset."));
    }

    @Test
    void testOnToolExecutionRendersSuccessAndErrorStatus() {
        FeatureChatPanel panel = createFeatureChatPanel(null);
        panel.onToolExecution(new ToolExecutionEvent("task:default", "1 + 1", 2, true, null));
        panel.onToolExecution(new ToolExecutionEvent("task:default", "if (", null, false, "Syntax Error: <bad>"));
        panel.onToolExecution(new ToolExecutionEvent("qa:default", "ignoredCall()", null, true, null));
        flushEdt();

        String html = panel.getOutputArea().getText();
        assertContainsAll(html, "Executed successfully", "Error: Syntax Error: &lt;bad&gt;");
        assertFalse(html.contains("<bad>"));
        assertFalse(html.contains("ignoredCall"));
    }

    @Test
    void testSendMessagePublishesUserMessageEvent() {
        ExploreChatPanel panel = createExploreChatPanel(directExecutor);
        assertSame(directExecutor, panel.getController().getTurnExecutor());
        panel.getController().sendMessage("Hello mentor");
        verify(publisher).publishEvent(new UserMessageEvent("qa:default", "user", "Hello mentor"));
    }

    @Test
    void testClosePreservesSharedExecutorWhileCancellingActiveTurn() throws Exception {
        ExecutorService executorService = new AiConfig().chatTurnExecutor();
        try {
            assertFalse(executorService.isShutdown());

            CountDownLatch turnStarted = new CountDownLatch(1);
            CountDownLatch turnInterrupted = new CountDownLatch(1);
            when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
                turnStarted.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    turnInterrupted.countDown();
                }
                return "done";
            });

            ExploreChatPanel panel = createExploreChatPanel(executorService);
            assertFalse(panel.getController().ownsTurnExecutor());

            JPanel host = new JPanel();
            host.add(panel);
            host.remove(panel);
            panel.removeNotify();
            assertFalse(executorService.isShutdown());

            sendOnEdt(panel, "Long running query");
            assertTrue(turnStarted.await(5, TimeUnit.SECONDS));
            assertTrue(panel.getController().isTurnActive());

            panel.close();
            assertTrue(turnInterrupted.await(5, TimeUnit.SECONDS));
            assertFalse(panel.getController().isTurnActive());
            assertTrue(panel.getModel().isTurnCancelled());
            assertFalse(executorService.isShutdown(), "Shared injected executor must not be shut down by close()");
        } finally {
            executorService.close();
        }
    }

    @Test
    void testFallbackDefaultExecutorOwnedAndClosedOnRemoveNotifyOrClose() {
        ChatPanel panel = new ChatPanel(aiService, planService, publisher, chatMemory, ChatModel.QA_SESSION_PREFIX,
                WorkflowPhase.EXPLORE, false, null, null);

        assertTrue(panel.getController().ownsTurnExecutor());
        ExecutorService fallbackExecutor = assertInstanceOf(ExecutorService.class,
                panel.getController().getTurnExecutor());
        assertFalse(fallbackExecutor.isShutdown());

        panel.removeNotify();
        assertTrue(fallbackExecutor.isShutdown());

        panel.addNotify();
        ExecutorService recreatedExecutor = assertInstanceOf(ExecutorService.class,
                panel.getController().getTurnExecutor());
        assertFalse(recreatedExecutor.isShutdown());

        panel.close();
        assertTrue(recreatedExecutor.isShutdown());
    }

    @Test
    void testSendMessageWithEmptyTextAndPendingImageShowsFeedbackAndPreservesImage() {
        ExploreChatPanel panel = createExploreChatPanel(directExecutor);
        panel.getModel().addPendingImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
        assertTrue(panel.getModel().hasPendingImages());

        panel.getController().sendMessage("   ");

        assertTrue(panel.getModel().hasPendingImages());
        assertTrue(panel.getOutputArea().getText().contains(ChatController.IMAGE_REQUIRES_TEXT_MESSAGE));
        verifyNoInteractions(publisher, aiService);
    }

    @Test
    void testTransientErrorWhenCurrentTurnPositiveAutoNudgesWhileTurnZeroDoesNot() {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);
        panel.getController().setErrorNudgeDelayMs(0);

        when(aiService.chat(anyString(), any(), eq("Bad API call"), any()))
                .thenThrow(new RuntimeException("Failed to generate content"));

        sendOnEdt(panel, "Bad API call");
        verify(aiService, times(1)).chat(anyString(), any(), anyString(), any());
        assertEquals(0, panel.getController().getConsecutiveAutoNudges());
        assertFalse(panel.getOutputArea().getText().contains("Auto-nudging Roxy to continue"));
        assertFalse(panel.getModel().isLoading());

        clearInvocations(aiService);
        AtomicInteger callCount = new AtomicInteger(0);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() == 1) {
                panel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 1, "CODE"));
                throw new RuntimeException("Failed to generate content");
            }
            return "Recovered and completed.";
        });

        sendOnEdt(panel, "Implement step");
        assertEquals(2, callCount.get());
        verify(aiService).chat(eq("task:default"), eq(WorkflowPhase.CODE), eq("Implement step"), any());
        verify(aiService).chat(eq("task:default"), eq(WorkflowPhase.CODE), eq(ChatController.AUTO_NUDGE_PROMPT), any());
        assertEquals(1, panel.getController().getConsecutiveAutoNudges());
        assertContainsAll(panel.getOutputArea().getText(), "Error: Failed to generate content",
                "Auto-nudging Roxy to continue (1/2)...", "Recovered and completed.");
        assertFalse(panel.getModel().isLoading());
    }

    @Test
    void testMaxToolTurnsTruncatedAndEmptyPostToolResponseAutoNudgeUpToCapAndResetOnUserAction() {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);

        AtomicInteger callCount = new AtomicInteger(0);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> callCount.incrementAndGet() == 2
                ? "Partial output\n\n---\n> ⚠️ **Response Truncated (finishReason: MAX_TOKENS)**: The response was cut off."
                : "Autonomous execution stopped: Maximum tool turns (5) exceeded.");
        sendOnEdt(panel, "Run long task");
        assertEquals(3, callCount.get());
        assertEquals(ChatController.MAX_AUTO_NUDGES, panel.getController().getConsecutiveAutoNudges());
        String html = panel.getOutputArea().getText();
        assertContainsAll(html, "Auto-nudging Roxy to continue (1/2)...", "Auto-nudging Roxy to continue (2/2)...");
        assertFalse(html.contains("Auto-nudging Roxy to continue (3/2)..."));
        assertFalse(panel.getModel().isLoading());

        AtomicInteger secondBatchCalls = new AtomicInteger(0);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            if (secondBatchCalls.incrementAndGet() == 1) {
                panel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
                return "⚠️ **Empty Response Returned (finishReason: STOP)** Roxy completed the turn without generating a visible text message.";
            }
            return "Final summary after empty response nudge.";
        });

        sendOnEdt(panel, "Next user message");
        assertTrue(panel.getOutputArea().getText().contains("Final summary after empty response nudge."));
    }

    @Test
    void testStopTurnAndPhaseActionsCancelPendingAutoNudgeTimerAndResetCounter() {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);
        panel.getController().setErrorNudgeDelayMs(5000);

        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            panel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 1, "CODE"));
            throw new RuntimeException("Failed to generate content");
        });

        sendOnEdt(panel, "Start work");
        assertEquals(1, panel.getController().getConsecutiveAutoNudges());
        assertNotNull(panel.getController().getAutoNudgeTimer());
        assertTrue(panel.getController().isTurnActive());
        assertTrue(panel.getModel().isLoading());
        assertTrue(panel.getStopButton().isEnabled());

        SwingTestUtils.runOnEdt(() -> panel.getController().stopTurn());
        assertEquals(0, panel.getController().getConsecutiveAutoNudges());
        assertNull(panel.getController().getAutoNudgeTimer());
        assertFalse(panel.getController().isTurnActive());
        assertFalse(panel.getModel().isLoading());
        assertTrue(panel.getOutputArea().getText().contains("Request cancelled by user."));
        verify(aiService, times(1)).chat(anyString(), any(), anyString(), any());

        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.REVIEW);
        sendOnEdt(panel, "Try with pending");
        assertEquals(0, panel.getController().getConsecutiveAutoNudges());
        assertNull(panel.getController().getAutoNudgeTimer());
    }

    @Test
    void testPerformResetSessionInQaModeArchivesNonEmptyTopicAndSkipsWhenEmpty() {
        ExploreChatPanel panel = createExploreChatPanel(directExecutor);
        SwingTestUtils.runOnEdt(() -> panel.getController().performResetSession());
        flushEdt();

        verify(chatMemory).clear("qa:default");
        verifyNoInteractions(memoryService);
        assertTrue(panel.getOutputArea().getText().contains("Started a new topic."));

        clearInvocations(chatMemory, aiService, memoryService);
        when(environmentService.getCurrentUser()).thenReturn("DevUser");
        when(aiService.generateTopicTitle(anyString())).thenReturn("RipGrep Search Architecture");
        when(memoryService.archiveTopicConversation(eq("RipGrep Search Architecture"), eq("DevUser"), anyString(),
                anyList())).thenReturn("20250514-153000-ripgrep-search-architecture.md");

        SwingTestUtils.runOnEdt(() -> {
            panel.appendMessage("user", "How does RipGrep search work in MemoryService?");
            panel.appendMessage("ai", "It searches global, project, and archive dirs.");
            panel.getController().performResetSession();
        });
        flushEdt();

        verify(chatMemory).clear("qa:default");
        verify(aiService).generateTopicTitle(contains("How does RipGrep search work"));
        verify(memoryService).archiveTopicConversation(eq("RipGrep Search Architecture"), eq("DevUser"), anyString(),
                argThat(list -> list.size() == 2));
        assertContainsAll(panel.getOutputArea().getText(), "Archived previous topic as", "RipGrep Search Architecture",
                "20250514-153000-ripgrep-search-architecture.md");
        verify(publisher).publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC,
                "20250514-153000-ripgrep-search-architecture.md"));
    }

    @Test
    void testApprovePhaseAndImageEncodingRunOnTurnExecutorOffEdt() {
        AtomicBoolean approveOnEdt = new AtomicBoolean(true);
        AtomicBoolean chatOnEdt = new AtomicBoolean(true);
        AtomicReference<List<byte[]>> capturedImages = new AtomicReference<>();

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        doAnswer(inv -> {
            approveOnEdt.set(SwingUtilities.isEventDispatchThread());
            when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
            when(planService.getPendingPhase()).thenReturn(null);
            return null;
        }).when(planService).approveTransition();

        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            chatOnEdt.set(SwingUtilities.isEventDispatchThread());
            capturedImages.set(inv.getArgument(3));
            return "Proceeding in CODE";
        });

        FeatureChatPanel panel = createFeatureChatPanel(null);
        panel.getModel().addPendingImage(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));

        SwingTestUtils.runOnEdt(() -> panel.getController().approvePhase());
        SwingTestUtils.awaitCondition(() -> capturedImages.get() != null);

        assertFalse(approveOnEdt.get());
        assertFalse(chatOnEdt.get());
        assertEquals(1, capturedImages.get().size());
        assertTrue(capturedImages.get().get(0).length > 0);
        assertFalse(panel.getModel().hasPendingImages());
    }

    @Test
    void testStopButtonCancelsTurnAndBlocksLateAutonomousTransitionsAndEvents() throws Exception {
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
        SwingTestUtils.runOnEdt(() -> {
            chatPanel.getInputArea().setText("Start coding");
            chatPanel.getSendButton().doClick();
        });
        assertTrue(callStarted.await(5, TimeUnit.SECONDS));

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop", true));
        flushEdt();
        SwingTestUtils.clickOnEdt(chatPanel.getStopButton());
        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Late transition", true));
        chatPanel.onToolExecution(new ToolExecutionEvent("task:default", "lateToolCall()", "ok", true, null));
        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 99, "CODE"));

        allowExit.countDown();
        SwingTestUtils.awaitCondition(() -> !chatPanel.getController().isTurnActive());
        flushEdt();

        String html = chatPanel.getOutputArea().getText();
        assertTrue(html.contains("Request cancelled by user"));
        assertFalse(html.contains("lateToolCall"));
        assertFalse(html.contains("Late transition"));
        assertEquals(1, chatCalls.get());
    }

    @Test
    void testWorkflowTransitionsApproveFlowAndDeferredBannerOrdering() {
        FeatureChatPanel chatPanel = createFeatureChatPanel(directExecutor);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason", true));
        flushEdt();
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        when(aiService.chat(anyString(), any(), anyString(), any())).thenReturn("Reviewing now");
        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason", true));
        flushEdt();
        verify(aiService, times(1)).chat(anyString(), any(), anyString(), any());

        clearInvocations(aiService);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready", false));
            return "FINAL_PLAN_SUMMARY";
        });

        sendOnEdt(chatPanel, "Build feature");
        flushEdt();

        String renderedHtml = chatPanel.getOutputArea().getText();
        assertTrue(renderedHtml.contains("FINAL_PLAN_SUMMARY"));
        assertTrue(chatPanel.getInlineActionPanel().isVisible());
        assertEquals("Approve Plan and proceed to Code", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.REVIEW, WorkflowPhase.PLAN, "All done", false));
        flushEdt();
        assertEquals("Approve Feature Request as complete", chatPanel.getTransitionLabel().getText());

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Need revision", false));
        flushEdt();
        assertEquals("Approve Plan Revision and return to Plan", chatPanel.getTransitionLabel().getText());

        clearInvocations(aiService, chatMemory);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);
        doAnswer(inv -> {
            when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
            return null;
        }).when(planService).approveTransition();
        when(aiService.chat(anyString(), any(), anyString(), any())).thenReturn("Revised plan");

        SwingTestUtils.runOnEdt(() -> chatPanel.getController().approvePhase());
        flushEdt();

        verify(aiService).chat(eq("task:default"), eq(WorkflowPhase.PLAN), contains("I approve the transition to Plan"),
                any());
        verify(chatMemory, never()).clear(anyString());

        clearInvocations(aiService, chatMemory);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);

        SwingTestUtils.runOnEdt(() -> chatPanel.getController().approvePhase());
        flushEdt();

        verify(aiService, never()).chat(anyString(), any(), anyString(), any());
        verify(chatMemory).clear("task:default");
        assertTrue(chatPanel.getOutputArea().getText().contains("Task complete"));
    }

    @Test
    void testLoadingStatePendingPhaseEnablementAndTurnStatusUpdates() {
        AppSettings appSettings = new AppSettings();
        appSettings.setMaxAgentToolTurns(5);
        when(settingsService.getSettings()).thenReturn(appSettings);

        FeatureChatPanel chatPanel = createFeatureChatPanel(null);
        List<Boolean> loadingStates = new ArrayList<>();
        chatPanel.addLoadingListener(loadingStates::add);

        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        chatPanel.setLoading(true);
        assertFalse(chatPanel.getApproveButton().isEnabled());
        assertFalse(chatPanel.getRejectButton().isEnabled());

        chatPanel.setLoading(false);
        assertTrue(chatPanel.getApproveButton().isEnabled());
        assertTrue(chatPanel.getRejectButton().isEnabled());
        assertEquals(List.of(true, false), loadingStates);

        assertEquals("Turns: 0 / 5", chatPanel.getTurnLabel().getText());
        ImplementationPlan plan = samplePlan(new ImplementationPlan.TechStep("Step 0", true),
                new ImplementationPlan.TechStep("Step 1", false));
        when(planService.getCurrentPlan()).thenReturn(plan);

        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 1/2 steps", chatPanel.getTurnLabel().getText());

        ImplementationPlan updatedPlan = samplePlan(new ImplementationPlan.TechStep("Step 0", true),
                new ImplementationPlan.TechStep("Step 1", true));
        when(planService.getCurrentPlan()).thenReturn(updatedPlan);
        chatPanel.onPlanUpdated(new PlanUpdatedEvent(updatedPlan));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 2/2 steps", chatPanel.getTurnLabel().getText());
    }

    @Test
    void testInputAreaUndoRedoActions() {
        ChatPanel chatPanel = createChatPanel();
        JTextArea inputArea = chatPanel.getInputArea();
        Action undoAction = inputArea.getActionMap().get("Undo");
        Action redoAction = inputArea.getActionMap().get("Redo");
        assertNotNull(undoAction);
        assertNotNull(redoAction);

        SwingTestUtils.runOnEdt(() -> {
            inputArea.setText("First edit");
            inputArea.append(" + Second edit");
        });
        assertEquals("First edit + Second edit", inputArea.getText());

        SwingTestUtils.runOnEdt(() -> undoAction.actionPerformed(null));
        assertEquals("First edit", inputArea.getText());

        SwingTestUtils.runOnEdt(() -> undoAction.actionPerformed(null));
        assertEquals("", inputArea.getText());

        SwingTestUtils.runOnEdt(() -> redoAction.actionPerformed(null));
        assertEquals("First edit", inputArea.getText());

        SwingTestUtils.runOnEdt(() -> redoAction.actionPerformed(null));
        assertEquals("First edit + Second edit", inputArea.getText());
    }

    @Test
    void testLoadImageAttachmentAsyncThumbnailAndChipRemoval(@TempDir Path tempDir) throws Exception {
        ChatPanel chatPanel = createChatPanel();
        BufferedImage src = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        BufferedImage thumb = ChatPanel.createThumbnail(src, ChatPanel.THUMBNAIL_SIZE);
        assertEquals(64, thumb.getWidth());
        assertEquals(64, thumb.getHeight());

        File imgFile = tempDir.resolve("sample.png").toFile();
        ImageIO.write(src, "png", imgFile);

        BufferedImage loaded = SwingTestUtils.awaitWorker(chatPanel.loadImageAttachmentAsync(imgFile));
        flushEdt();
        assertNotNull(loaded);
        assertEquals(120, loaded.getWidth());
        assertTrue(chatPanel.getModel().hasPendingImages());
        assertEquals(1, chatPanel.getModel().getPendingImages().size());

        JButton removeChipButton = SwingTestUtils.findComponent(chatPanel, JButton.class,
                b -> b != chatPanel.getRejectButton() && b.getIcon() instanceof FontIcon fi
                        && fi.getIkon() == Codicons.CLOSE);
        assertNotNull(removeChipButton);
        SwingTestUtils.clickOnEdt(removeChipButton);
        assertFalse(chatPanel.getModel().hasPendingImages());
    }

    @Test
    void testFileMentionAutocompleteKeyboardNavigationAndCacheInvalidation() {
        ChatPanel chatPanel = createFeatureChatPanel(directExecutor);
        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles()).thenReturn(List.of("src/main/java/org/roxycode/app/ui/ChatPanel.java",
                "src/main/java/org/roxycode/app/ui/ChatController.java",
                "src/main/java/org/roxycode/app/ui/ChatModel.java"));

        FileMentionPopup mentionPopup = chatPanel.getFileMentionPopup();
        mentionPopup.setBackgroundExecutor(Runnable::run);
        chatPanel.setGrepService(grepService);
        verify(grepService, times(1)).listFiles();

        JTextArea inputArea = chatPanel.getInputArea();
        SwingTestUtils.runOnEdt(() -> {
            inputArea.setText("Review @Chat");
            inputArea.setCaretPosition(inputArea.getText().length());
        });
        flushEdt();

        assertTrue(mentionPopup.isPopupVisible());
        assertEquals(3, mentionPopup.getListModel().size());
        assertEquals(0, mentionPopup.getCandidateList().getSelectedIndex());

        ActionMap am = inputArea.getActionMap();
        Action downAction = am.get("autocomplete-or-down");
        Action upAction = am.get("autocomplete-or-up");
        Action enterAction = am.get("send-message");
        Action tabAction = am.get("autocomplete-or-tab");
        Action escapeAction = am.get("dismiss-autocomplete");

        SwingTestUtils.runOnEdt(() -> downAction.actionPerformed(null));
        assertEquals(1, mentionPopup.getCandidateList().getSelectedIndex());

        SwingTestUtils.runOnEdt(() -> upAction.actionPerformed(null));
        assertEquals(0, mentionPopup.getCandidateList().getSelectedIndex());
        SwingTestUtils.runOnEdt(() -> upAction.actionPerformed(null));
        assertEquals(2, mentionPopup.getCandidateList().getSelectedIndex());

        SwingTestUtils.runOnEdt(() -> enterAction.actionPerformed(null));
        flushEdt();
        assertFalse(mentionPopup.isPopupVisible());
        assertEquals("Review @src/main/java/org/roxycode/app/ui/ChatController.java ", inputArea.getText());
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

        chatPanel.onProjectChanged(new ProjectChangedEvent(Path.of(".")));
        verify(grepService, times(2)).listFiles();

        chatPanel.onFileChanged(new FileChangedEvent("src/NewFile.java"));
        verify(grepService, times(3)).listFiles();
    }
}
