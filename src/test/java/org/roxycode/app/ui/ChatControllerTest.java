package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
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
import org.roxycode.app.service.ProjectService;

class ChatControllerTest extends ChatTestFixture {

    @Test
    void testPerformResetSessionResetForProjectChangeAndExecutorLifecycle() throws Exception {
        ChatPanel panel = createFeatureChatPanel(null);
        panel.model.setCurrentTurn(3);
        panel.controller.performResetSession();
        flushEdt();

        verify(planService).resetWorkflow();
        verify(chatMemory).clear("task:default");
        assertEquals(0, panel.model.getCurrentTurn());
        assertEquals(0L, panel.controller.activeTurnId);
        assertTrue(panel.outputArea.getText().contains("Session reset."));

        clearInvocations(planService, chatMemory);
        panel.appendMessage("user", "Project-specific message");
        panel.model.addPendingImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
        panel.controller.resetForProjectChange();
        flushEdt();

        verify(planService).resetWorkflow();
        verify(chatMemory, never()).clear(anyString());
        assertFalse(panel.model.hasPendingImages());
        assertTrue(panel.getConversationMessages().isEmpty());

        ExecutorService sharedExecutor = new AiConfig().chatTurnExecutor();
        try {
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

            ChatPanel sharedPanel = createExploreChatPanel(sharedExecutor);
            assertFalse(sharedPanel.controller.turnExecutorOwned);
            sendOnEdt(sharedPanel, "Long running query");
            assertTrue(turnStarted.await(5, TimeUnit.SECONDS));
            assertTrue(sharedPanel.controller.isTurnActive());

            sharedPanel.close();
            assertTrue(turnInterrupted.await(5, TimeUnit.SECONDS));
            assertFalse(sharedPanel.controller.isTurnActive());
            assertTrue(sharedPanel.model.isTurnCancelled());
            assertFalse(sharedExecutor.isShutdown());
        } finally {
            sharedExecutor.close();
        }

        ChatPanel ownedPanel = new ChatPanel(aiService, planService, publisher, chatMemory, ChatModel.QA_SESSION_PREFIX,
                WorkflowPhase.EXPLORE, false, null, null);
        assertTrue(ownedPanel.controller.turnExecutorOwned);
        ExecutorService ownedExecutor = assertInstanceOf(ExecutorService.class, ownedPanel.controller.turnExecutor);
        ownedPanel.removeNotify();
        assertTrue(ownedExecutor.isShutdown());
        ownedPanel.addNotify();
        ExecutorService recreated = assertInstanceOf(ExecutorService.class, ownedPanel.controller.turnExecutor);
        assertFalse(recreated.isShutdown());
        ownedPanel.close();
        assertTrue(recreated.isShutdown());
    }

    @Test
    void testNewTopicResetInQaModeHandlesEmptySaveDiscardAndCancelChoices() {
        ChatPanel panel = createExploreChatPanel(directExecutor);
        AtomicBoolean dialogInvoked = new AtomicBoolean(false);
        panel.setNewTopicDialogPresenter(defaultTitle -> {
            dialogInvoked.set(true);
            return new ChatPanel.NewTopicChoice(true, true, defaultTitle);
        });

        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertFalse(dialogInvoked.get());
        verify(chatMemory).clear("qa:default");
        verifyNoInteractions(memoryService, aiService);

        clearInvocations(chatMemory, memoryService, publisher);
        AtomicReference<String> capturedDefaultTitle = new AtomicReference<>();
        panel.setNewTopicDialogPresenter(defaultTitle -> {
            capturedDefaultTitle.set(defaultTitle);
            return new ChatPanel.NewTopicChoice(false, false, "");
        });
        SwingTestUtils.runOnEdt(() -> {
            panel.appendMessage("user", "How does RipGrep search work in MemoryService?");
            panel.appendMessage("ai", "It searches global, project, and archive dirs.");
        });
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertEquals("How does RipGrep search work in MemoryService", capturedDefaultTitle.get());
        assertEquals(2, panel.getConversationMessages().size());
        verifyNoInteractions(chatMemory, memoryService, aiService);

        panel.setNewTopicDialogPresenter(defaultTitle -> new ChatPanel.NewTopicChoice(true, false, "Ignored Title"));
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        verify(chatMemory).clear("qa:default");
        verifyNoInteractions(memoryService, aiService);
        assertTrue(panel.getConversationMessages().isEmpty());

        clearInvocations(chatMemory, memoryService, publisher);
        when(environmentService.getCurrentUser()).thenReturn("DevUser");
        when(memoryService.archiveTopicConversation(eq("RipGrep Search Architecture"), eq("DevUser"), anyString(),
                anyList())).thenReturn("20250514-153000-ripgrep-search-architecture.md");
        panel.setNewTopicDialogPresenter(
                defaultTitle -> new ChatPanel.NewTopicChoice(true, true, "  # **RipGrep Search Architecture!**  "));

        SwingTestUtils.runOnEdt(() -> {
            panel.appendMessage("user", "How does RipGrep search work in MemoryService?");
            panel.appendMessage("ai", "It searches global, project, and archive dirs.");
        });
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();

        verify(chatMemory).clear("qa:default");
        verify(memoryService).archiveTopicConversation(eq("RipGrep Search Architecture"), eq("DevUser"), anyString(),
                argThat(list -> list.size() == 2));
        assertContainsAll(panel.outputArea.getText(), "Archived previous topic as", "RipGrep Search Architecture",
                "20250514-153000-ripgrep-search-architecture.md");
        verify(publisher).publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC,
                "20250514-153000-ripgrep-search-architecture.md"));
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

        ChatPanel chatPanel = createFeatureChatPanel(null);
        SwingTestUtils.runOnEdt(() -> {
            chatPanel.inputArea.setText("Start coding");
            chatPanel.sendButton.doClick();
        });
        assertTrue(callStarted.await(5, TimeUnit.SECONDS));

        chatPanel.onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop", true));
        flushEdt();
        SwingTestUtils.clickOnEdt(chatPanel.stopButton);
        chatPanel.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Late transition", true));
        chatPanel.onToolExecution(new ToolExecutionEvent("task:default", "lateToolCall()", "ok", true, null));
        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 99, "CODE"));

        allowExit.countDown();
        SwingTestUtils.awaitCondition(() -> !chatPanel.controller.isTurnActive());
        flushEdt();

        String html = chatPanel.outputArea.getText();
        assertTrue(html.contains("Request cancelled by user"));
        assertFalse(html.contains("lateToolCall"));
        assertFalse(html.contains("Late transition"));
        assertEquals(1, chatCalls.get());
    }

    @Test
    void testWorkflowTransitionsApproveFlowAndDeferredBannerOrdering() {
        ChatPanel chatPanel = createFeatureChatPanel(directExecutor);
        assertSame(directExecutor, chatPanel.controller.turnExecutor);
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
        assertTrue(chatPanel.outputArea.getText().contains("FINAL_PLAN_SUMMARY"));
        assertTrue(chatPanel.inlineActionPanel.isVisible());
        assertEquals("Approve Plan and proceed to Code", chatPanel.transitionLabel.getText());

        clearInvocations(aiService);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenReturn("Let's revise the plan.");
        chatPanel.controller.rejectPhase();
        flushEdt();
        verify(planService).rejectTransition();
        verify(aiService).chat(eq("task:default"), eq(WorkflowPhase.PLAN),
                contains("I have rejected the transition to Code"), any());

        clearInvocations(aiService, chatMemory);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);
        SwingTestUtils.runOnEdt(() -> chatPanel.controller.approvePhase());
        flushEdt();
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());
        verify(chatMemory).clear("task:default");
        assertTrue(chatPanel.outputArea.getText().contains("Task complete"));

        AtomicBoolean approveOnEdt = new AtomicBoolean(true);
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
            capturedImages.set(inv.getArgument(3));
            return "Proceeding in CODE";
        });

        ChatPanel asyncPanel = createFeatureChatPanel(null);
        asyncPanel.model.addPendingImage(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
        SwingTestUtils.runOnEdt(() -> asyncPanel.controller.approvePhase());
        SwingTestUtils.awaitCondition(() -> capturedImages.get() != null);

        assertFalse(approveOnEdt.get());
        assertEquals(1, capturedImages.get().size());
        assertFalse(asyncPanel.model.hasPendingImages());
    }

    @Test
    void testLoadingStateTurnStatusImageAttachmentsAndComposerGuards(@TempDir Path tempDir) throws Exception {
        AppSettings appSettings = new AppSettings();
        appSettings.setMaxAgentToolTurns(5);
        when(settingsService.getSettings()).thenReturn(appSettings);

        ChatPanel chatPanel = createFeatureChatPanel(directExecutor);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        chatPanel.setLoading(true);
        assertFalse(chatPanel.sendButton.isVisible());
        assertTrue(chatPanel.stopButton.isVisible());
        assertFalse(chatPanel.approveButton.isEnabled());

        chatPanel.setLoading(false);
        assertTrue(chatPanel.sendButton.isVisible());
        assertTrue(chatPanel.approveButton.isEnabled());

        ImplementationPlan plan = samplePlan(new ImplementationPlan.TechStep("Step 0", true),
                new ImplementationPlan.TechStep("Step 1", false));
        when(planService.getCurrentPlan()).thenReturn(plan);
        chatPanel.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        flushEdt();
        assertEquals("Turns: 2 / 5 | Plan: 1/2 steps", chatPanel.turnLabel.getText());

        BufferedImage src = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        File imgFile = tempDir.resolve("sample.png").toFile();
        ImageIO.write(src, "png", imgFile);
        SwingTestUtils.awaitFuture(chatPanel.loadImageAttachmentAsync(imgFile));
        assertTrue(chatPanel.model.hasPendingImages());

        chatPanel.controller.sendMessage("   ");
        assertTrue(chatPanel.outputArea.getText().contains(ChatController.IMAGE_REQUIRES_TEXT_MESSAGE));

        JButton removeChipButton = SwingTestUtils.findComponent(chatPanel, JButton.class,
                b -> b != chatPanel.rejectButton && b.getIcon() instanceof FontIcon fi
                        && fi.getIkon() == Codicons.CLOSE);
        SwingTestUtils.clickOnEdt(removeChipButton);
        assertFalse(chatPanel.model.hasPendingImages());

        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles()).thenReturn(List.of("src/main/java/org/roxycode/app/ui/ChatPanel.java"));
        chatPanel.fileMentionPopup.setBackgroundExecutor(Runnable::run);
        chatPanel.setGrepService(grepService);

        JTextArea inputArea = chatPanel.inputArea;
        SwingTestUtils.runOnEdt(() -> {
            inputArea.setText("Inspect");
            inputArea.setCaretPosition(inputArea.getText().length());
            chatPanel.mentionButton.doClick();
        });
        flushEdt();
        assertEquals("Inspect @", inputArea.getText());
        assertTrue(chatPanel.fileMentionPopup.isPopupVisible());

        SwingTestUtils.runOnEdt(() -> inputArea.getActionMap().get("send-message").actionPerformed(null));
        flushEdt();
        assertFalse(chatPanel.fileMentionPopup.isPopupVisible());
        assertEquals("Inspect @src/main/java/org/roxycode/app/ui/ChatPanel.java ", inputArea.getText());

        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(false);
        chatPanel.setProjectService(projectService);
        assertFalse(chatPanel.inputArea.isEnabled());
        assertEquals(ChatPanel.NO_PROJECT_PLACEHOLDER_TEXT,
                chatPanel.inputArea.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));

        when(projectService.hasActiveProject()).thenReturn(true);
        chatPanel.onProjectChanged(new ProjectChangedEvent(Path.of(".")));
        flushEdt();
        assertTrue(chatPanel.inputArea.isEnabled());
    }
}
