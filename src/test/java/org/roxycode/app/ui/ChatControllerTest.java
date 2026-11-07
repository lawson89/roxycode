package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import javax.swing.JComboBox;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import java.util.concurrent.CompletableFuture;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.AiMemoryConfig;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.*;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.ProjectService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

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
    void testNewTopicResetInQaModeAndResetSessionPromptInTaskMode() {
        ChatPanel panel = createExploreChatPanel(directExecutor);

        // 1. Empty Q&A conversation resets immediately without showing userInputPromptPanel
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertFalse(panel.userInputPromptPanel.isVisible());
        verify(chatMemory).clear("qa:default");
        verifyNoInteractions(memoryService, aiService);

        // 2. Non-empty Q&A conversation renders inline New Topic prompt; Cancel hides prompt and keeps messages
        clearInvocations(chatMemory, memoryService, publisher);
        SwingTestUtils.runOnEdt(() -> {
            panel.appendMessage("user", "How does RipGrep search work in MemoryService?");
            panel.appendMessage("ai", "It searches global, project, and archive dirs.");
        });
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertTrue(panel.userInputPromptPanel.isVisible());

        JTextField topicField = SwingTestUtils.findComponent(panel.userInputPromptPanel, JTextField.class);
        assertNotNull(topicField);
        assertEquals("How does RipGrep search work in MemoryService", topicField.getText());

        SettingsPanel.applyTheme("Light");
        SwingTestUtils.runOnEdt(() -> SwingUtilities.updateComponentTreeUI(panel));
        assertInstanceOf(CompoundBorder.class, panel.userInputPromptPanel.getBorder());
        MatteBorder promptAccentStripe = assertInstanceOf(MatteBorder.class,
                ((CompoundBorder) panel.userInputPromptPanel.getBorder()).getOutsideBorder());
        assertEquals(4, promptAccentStripe.getBorderInsets(panel.userInputPromptPanel).left);

        JButton saveBtn = SwingTestUtils.findButton(panel.userInputPromptPanel,
                ChatPromptBanner.SAVE_TOPIC_BUTTON_TEXT);
        JButton discardBtnInitial = SwingTestUtils.findButton(panel.userInputPromptPanel,
                ChatPromptBanner.DISCARD_TOPIC_BUTTON_TEXT);
        JButton cancelBtn = SwingTestUtils.findButton(panel.userInputPromptPanel, ChatPromptBanner.CANCEL_BUTTON_TEXT);
        assertNotNull(saveBtn);
        assertNotNull(discardBtnInitial);
        assertNotNull(cancelBtn);
        assertEquals(UiComponents.ACCENT_BUTTON_STYLE, saveBtn.getClientProperty(FlatClientProperties.STYLE));
        assertEquals(UiComponents.SUBTLE_RED_BUTTON_STYLE,
                discardBtnInitial.getClientProperty(FlatClientProperties.STYLE));
        assertNull(cancelBtn.getClientProperty(FlatClientProperties.STYLE));
        assertEquals(Color.WHITE, assertInstanceOf(FontIcon.class, saveBtn.getIcon()).getIconColor());
        assertNotEquals(Color.WHITE, assertInstanceOf(FontIcon.class, cancelBtn.getIcon()).getIconColor());

        SettingsPanel.applyTheme("Dark");
        SwingTestUtils.runOnEdt(() -> SwingUtilities.updateComponentTreeUI(panel));
        SwingTestUtils.clickOnEdt(cancelBtn);
        flushEdt();
        assertFalse(panel.userInputPromptPanel.isVisible());
        assertEquals(2, panel.getConversationMessages().size());
        verifyNoInteractions(chatMemory, memoryService, aiService);

        // Escape key on New Topic prompt cancels without clearing messages
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertTrue(panel.userInputPromptPanel.isVisible());
        ActionListener escapeAction = panel.userInputPromptPanel
                .getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        assertNotNull(escapeAction);
        SwingTestUtils
                .runOnEdt(() -> escapeAction.actionPerformed(new ActionEvent(panel.userInputPromptPanel, 0, "esc")));
        flushEdt();
        assertFalse(panel.userInputPromptPanel.isVisible());
        assertEquals(2, panel.getConversationMessages().size());

        // 3. Inline New Topic prompt -> No, Discard clears chat without archiving
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertTrue(panel.userInputPromptPanel.isVisible());
        JButton discardBtn = SwingTestUtils.findButton(panel.userInputPromptPanel,
                ChatPromptBanner.DISCARD_TOPIC_BUTTON_TEXT);
        assertNotNull(discardBtn);
        SwingTestUtils.clickOnEdt(discardBtn);
        flushEdt();
        assertFalse(panel.userInputPromptPanel.isVisible());
        verify(chatMemory).clear("qa:default");
        verifyNoInteractions(memoryService, aiService);
        assertTrue(panel.getConversationMessages().isEmpty());

        // 4. Inline New Topic prompt -> Yes, Save Topic (and Enter key action) archives with edited title
        clearInvocations(chatMemory, memoryService, publisher);
        when(environmentService.getCurrentUser()).thenReturn("DevUser");
        when(memoryService.archiveTopicConversation(eq("RipGrep Search Architecture"), eq("DevUser"), anyString(),
                anyList())).thenReturn("20250514-153000-ripgrep-search-architecture.md");

        SwingTestUtils.runOnEdt(() -> {
            panel.appendMessage("user", "How does RipGrep search work in MemoryService?");
            panel.appendMessage("ai", "It searches global, project, and archive dirs.");
        });
        SwingTestUtils.clickOnEdt(panel.resetSessionButton);
        flushEdt();
        assertTrue(panel.userInputPromptPanel.isVisible());

        JTextField saveTopicField = SwingTestUtils.findComponent(panel.userInputPromptPanel, JTextField.class);
        assertNotNull(saveTopicField);
        SwingTestUtils.runOnEdt(() -> {
            saveTopicField.setText("  # **RipGrep Search Architecture!**  ");
            saveTopicField.postActionEvent();
        });
        flushEdt();

        assertFalse(panel.userInputPromptPanel.isVisible());
        verify(chatMemory).clear("qa:default");
        verify(memoryService).archiveTopicConversation(eq("RipGrep Search Architecture"), eq("DevUser"), anyString(),
                argThat(list -> list.size() == 2));
        assertContainsAll(panel.outputArea.getText(), "Archived previous topic as", "RipGrep Search Architecture",
                "20250514-153000-ripgrep-search-architecture.md");
        verify(publisher).publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC,
                "20250514-153000-ripgrep-search-architecture.md"));

        // 5. Task Mode inline Reset Session prompt: Cancel vs Yes, Reset
        ChatPanel taskPanel = createFeatureChatPanel(directExecutor);
        clearInvocations(planService, chatMemory);
        SwingTestUtils.clickOnEdt(taskPanel.resetSessionButton);
        flushEdt();
        assertTrue(taskPanel.userInputPromptPanel.isVisible());

        assertInstanceOf(CompoundBorder.class, taskPanel.inlineActionPanel.getBorder());
        assertEquals(UiComponents.ACCENT_BUTTON_STYLE,
                taskPanel.approveButton.getClientProperty(FlatClientProperties.STYLE));
        assertNull(taskPanel.viewPlanButton.getClientProperty(FlatClientProperties.STYLE));
        assertEquals(UiComponents.SUBTLE_RED_BUTTON_STYLE,
                taskPanel.rejectButton.getClientProperty(FlatClientProperties.STYLE));

        JButton cancelResetBtn = SwingTestUtils.findButton(taskPanel.userInputPromptPanel,
                ChatPromptBanner.CANCEL_BUTTON_TEXT);
        assertNotNull(cancelResetBtn);
        ActionListener resetEscape = taskPanel.userInputPromptPanel
                .getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        assertNotNull(resetEscape);
        SwingTestUtils
                .runOnEdt(() -> resetEscape.actionPerformed(new ActionEvent(taskPanel.userInputPromptPanel, 0, "esc")));
        flushEdt();
        assertFalse(taskPanel.userInputPromptPanel.isVisible());
        verify(planService, never()).resetWorkflow();

        SwingTestUtils.clickOnEdt(taskPanel.resetSessionButton);
        flushEdt();
        assertTrue(taskPanel.userInputPromptPanel.isVisible());
        JButton confirmResetBtn = SwingTestUtils.findButton(taskPanel.userInputPromptPanel,
                ChatPromptBanner.CONFIRM_RESET_BUTTON_TEXT);
        assertNotNull(confirmResetBtn);
        SwingTestUtils.clickOnEdt(confirmResetBtn);
        flushEdt();
        assertFalse(taskPanel.userInputPromptPanel.isVisible());
        verify(planService).resetWorkflow();
        verify(chatMemory).clear("task:default");
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

        chatPanel.controller
                .onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop", true));
        flushEdt();
        SwingTestUtils.clickOnEdt(chatPanel.stopButton);
        chatPanel.controller.onWorkflowTransition(
                transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Late transition", true));
        chatPanel.controller
                .onToolExecution(new ToolExecutionEvent("task:default", "lateToolCall()", "ok", true, null));
        chatPanel.controller.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 99, "CODE"));

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

        chatPanel.controller
                .onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.PLAN, "Test reason", true));
        flushEdt();
        verify(aiService, never()).chat(anyString(), any(), anyString(), any());

        when(aiService.chat(anyString(), any(), anyString(), any())).thenReturn("Reviewing now");
        chatPanel.controller
                .onWorkflowTransition(transitionEvent(WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Test reason", true));
        flushEdt();
        verify(aiService, times(1)).chat(eq("task:default"), any(), contains("independent pull-request audit"), any());

        clearInvocations(aiService);
        when(aiService.chat(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            chatPanel.controller
                    .onWorkflowTransition(transitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready", false));
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
        assertEquals("Image", chatPanel.attachImageButton.getText());
        assertEquals("Send message (Enter)", chatPanel.sendButton.getToolTipText());
        assertEquals("Stop active turn (Esc)", chatPanel.stopButton.getToolTipText());
        String workingPlaceholder = String
                .valueOf(chatPanel.inputArea.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertContainsAll(workingPlaceholder, "Roxy is working", "Press Esc or click Stop to cancel");

        ImplementationPlan plan = samplePlan(new ImplementationPlan.TechStep("Step 0", true),
                new ImplementationPlan.TechStep("Step 1", false));
        when(planService.getCurrentPlan()).thenReturn(plan);
        chatPanel.controller.onAgentTurn(new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
        flushEdt();
        assertEquals("Turn 2/5 • 1/2 steps", chatPanel.telemetryBar.turnLabel.getText());
        assertContainsAll(String.valueOf(chatPanel.inputArea.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT)),
                "Turn 2/5 • 1/2 steps");

        SwingTestUtils.runOnEdt(() -> chatPanel.getActionMap().get("stop-active-turn").actionPerformed(null));
        flushEdt();
        assertFalse(chatPanel.isLoading());
        assertTrue(chatPanel.sendButton.isVisible());
        assertTrue(chatPanel.approveButton.isEnabled());
        assertEquals(ChatPanel.DEFAULT_PLACEHOLDER_TEXT,
                chatPanel.inputArea.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));

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
        chatPanel.controller.onProjectChanged(new ProjectChangedEvent(Path.of(".")));
        flushEdt();
        assertTrue(chatPanel.inputArea.isEnabled());
    }

    @Test
    void testPerConversationTokenTelemetryAndIsolation() {
        ChatPanel qaPanel = createExploreChatPanel(directExecutor);
        ChatPanel taskPanel = createFeatureChatPanel(directExecutor);

        // 1. Isolation: qa:default event ignored by taskPanel
        TokenUsageEvent qaEvent = new TokenUsageEvent("qa:default", "gemini-1.5-pro", 1_000_000, 10_000, 8_000, 2_000,
                500, 200, 10_700, 0.005);
        qaPanel.controller.onTokenUsage(qaEvent);
        taskPanel.controller.onTokenUsage(qaEvent);
        flushEdt();

        assertEquals("Ctx: 10.0k / 1.0M (1%)", qaPanel.telemetryBar.ctxLabel.getText());
        assertEquals("Cache: 80%", qaPanel.telemetryBar.cacheLabel.getText());
        assertEquals("Context window usage: 10.0k / 1.0M tokens (1%)", qaPanel.telemetryBar.ctxLabel.getToolTipText());
        assertEquals("Implicit cache hit: 8.0k / 10.0k input tokens (80%)",
                qaPanel.telemetryBar.cacheLabel.getToolTipText());
        assertEquals("Ctx: 0 / 1.0M (0%)", taskPanel.telemetryBar.ctxLabel.getText());

        // 2. task:default event updates taskPanel
        TokenUsageEvent taskEvent = new TokenUsageEvent("task:default", "gemini-1.5-pro", 1_000_000, 20_000, 10_000,
                10_000, 1_000, 500, 21_500, 0.012);
        taskPanel.controller.onTokenUsage(taskEvent);
        flushEdt();

        assertEquals("Ctx: 20.0k / 1.0M (2%)", taskPanel.telemetryBar.ctxLabel.getText());
        assertEquals("Cache: 50%", taskPanel.telemetryBar.cacheLabel.getText());
        assertEquals("Implicit cache hit: 10.0k / 20.0k input tokens (50%)",
                taskPanel.telemetryBar.cacheLabel.getToolTipText());

        // 3. Reset session resets telemetry for that panel
        taskPanel.controller.performResetSession();
        flushEdt();
        assertEquals("Ctx: 0 / 1.0M (0%)", taskPanel.telemetryBar.ctxLabel.getText());
        assertEquals("Cache: 0%", taskPanel.telemetryBar.cacheLabel.getText());
        assertEquals("Ctx: 10.0k / 1.0M (1%)", qaPanel.telemetryBar.ctxLabel.getText()); // Still there

        // 4. Project change resets telemetry
        qaPanel.controller.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));
        flushEdt();
        assertEquals("Ctx: 0 / 1.0M (0%)", qaPanel.telemetryBar.ctxLabel.getText());
    }

    @Test
    void testPerProjectSessionPersistenceRestoreAndResetCleanup(@TempDir Path tempDir) {
        AiMemoryConfig memoryConfig = new AiMemoryConfig();
        ChatMemory realMemory = memoryConfig.chatMemory(new InMemoryChatMemoryRepository(), settingsService);

        Path projectAData = tempDir.resolve("project-a-data");
        Path projectBData = tempDir.resolve("project-b-data");
        AtomicReference<Path> activeDataDir = new AtomicReference<>(projectAData);

        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> activeDataDir.get().resolve(inv.getArgument(0, String.class)));

        ChatPanel taskPanel = new ChatPanel(aiService, planService, publisher, realMemory,
                ChatModel.TASK_SESSION_PREFIX, null, true, settingsService, directExecutor);
        taskPanel.setProjectService(projectService);

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(aiService.chat(eq("task:default"), any(), eq("Create plan"), any())).thenAnswer(inv -> {
            realMemory.add("task:default",
                    List.of(new UserMessage("Create plan"), new AssistantMessage("Plan submitted.")));
            taskPanel.controller.onToolExecution(new ToolExecutionEvent("task:default",
                    "planService.submitPlan('Title', 'Goal', [], ['Step 1'])", "ok", true, null));
            taskPanel.controller.onTokenUsage(new TokenUsageEvent("task:default", "gemini-1.5-pro", 1_000_000, 25_000,
                    20_000, 5_000, 600, 100, 25_700, 0.008));
            return "Plan submitted.";
        });

        sendOnEdt(taskPanel, "Create plan");
        flushEdt();

        Path projectASessionFile = projectAData
                .resolve(ChatController.resolveActiveChatFilename(ChatModel.TASK_CONVERSATION_ID));
        assertTrue(Files.exists(projectASessionFile));
        assertEquals(2, realMemory.get("task:default").size());
        assertEquals(3, taskPanel.exportMessages().size());
        assertEquals("Ctx: 25.0k / 1.0M (3%)", taskPanel.telemetryBar.ctxLabel.getText());

        // Switch to Project B: clears transcript, ChatMemory, and telemetry without deleting Project A's session file
        activeDataDir.set(projectBData);
        when(planService.getPendingPhase()).thenReturn(null);
        taskPanel.controller.onProjectChanged(new ProjectChangedEvent(tempDir.resolve("project-b")));
        flushEdt();

        assertTrue(Files.exists(projectASessionFile));
        assertTrue(realMemory.get("task:default").isEmpty());
        assertTrue(taskPanel.exportMessages().isEmpty());
        assertEquals("Ctx: 0 / 1.0M (0%)", taskPanel.telemetryBar.ctxLabel.getText());
        assertFalse(taskPanel.inlineActionPanel.isVisible());

        // Switch back to Project A with pending approval: restores transcript, tool card, ChatMemory, telemetry &
        // banner
        activeDataDir.set(projectAData);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        taskPanel.controller.onProjectChanged(new ProjectChangedEvent(tempDir.resolve("project-a")));
        flushEdt();

        assertEquals(2, realMemory.get("task:default").size());
        assertEquals("Create plan", realMemory.get("task:default").get(0).getText());
        assertEquals("Plan submitted.", realMemory.get("task:default").get(1).getText());
        assertEquals(3, taskPanel.exportMessages().size());
        assertTrue(taskPanel.exportMessages().get(1).isTool());
        assertContainsAll(taskPanel.outputArea.getText(), "Create plan", "Plan submitted.", "submitPlan");
        assertEquals("Ctx: 25.0k / 1.0M (3%)", taskPanel.telemetryBar.ctxLabel.getText());
        assertEquals("Cache: 80%", taskPanel.telemetryBar.cacheLabel.getText());
        assertTrue(taskPanel.inlineActionPanel.isVisible());
        assertEquals(PlanService.APPROVAL_PLAN_TO_CODE, taskPanel.transitionLabel.getText());

        // Startup restoration on a newly created ChatPanel (and idempotent second setProjectService call)
        realMemory.clear("task:default");
        ChatPanel restartedPanel = new ChatPanel(aiService, planService, publisher, realMemory,
                ChatModel.TASK_SESSION_PREFIX, null, true, settingsService, directExecutor);
        clearInvocations(projectService);
        restartedPanel.setProjectService(projectService);
        flushEdt();
        verify(projectService, times(1)).resolveProjectDataPath(anyString());
        restartedPanel.setProjectService(projectService);
        verify(projectService, times(1)).resolveProjectDataPath(anyString());
        assertEquals(2, realMemory.get("task:default").size());
        assertEquals(3, restartedPanel.exportMessages().size());
        assertTrue(restartedPanel.inlineActionPanel.isVisible());

        // Reset session deletes the persisted session file
        restartedPanel.controller.performResetSession();
        flushEdt();
        assertFalse(Files.exists(projectASessionFile));
        assertTrue(realMemory.get("task:default").isEmpty());
    }

    @Test
    void testUserInputPromptCardsConversationFilteringAndLifecycleCleanup() throws Exception {
        ChatPanel qaPanel = createExploreChatPanel(directExecutor);
        ChatPanel taskPanel = createFeatureChatPanel(directExecutor);

        // 1. Conversation filtering: qa:default event ignored by taskPanel
        CompletableFuture<Object> confirmFuture = new CompletableFuture<>();
        UserInputRequestEvent confirmEvent = new UserInputRequestEvent("qa:default",
                UserInputRequestEvent.PromptType.CONFIRM, "Proceed with refactor?", "Affects 4 files", null, List.of(),
                confirmFuture);
        taskPanel.controller.onUserInputRequest(confirmEvent);
        qaPanel.controller.onUserInputRequest(confirmEvent);
        flushEdt();

        assertFalse(taskPanel.userInputPromptPanel.isVisible());
        assertTrue(qaPanel.userInputPromptPanel.isVisible());

        JButton yesButton = SwingTestUtils.findButton(qaPanel.userInputPromptPanel, "Yes");
        JButton noButton = SwingTestUtils.findButton(qaPanel.userInputPromptPanel, "No");
        assertNotNull(yesButton);
        assertNotNull(noButton);
        assertEquals(UiComponents.ACCENT_BUTTON_STYLE, yesButton.getClientProperty(FlatClientProperties.STYLE));
        assertNull(noButton.getClientProperty(FlatClientProperties.STYLE));
        SwingTestUtils.clickOnEdt(yesButton);
        assertEquals(Boolean.TRUE, confirmFuture.get(5, TimeUnit.SECONDS));
        assertFalse(qaPanel.userInputPromptPanel.isVisible());

        // Escape on CONFIRM prompt resolves to Boolean.FALSE and hides prompt
        CompletableFuture<Object> escapeConfirmFuture = new CompletableFuture<>();
        qaPanel.controller
                .onUserInputRequest(new UserInputRequestEvent("qa:default", UserInputRequestEvent.PromptType.CONFIRM,
                        "Confirm via Esc?", null, null, List.of(), escapeConfirmFuture));
        flushEdt();
        assertTrue(qaPanel.userInputPromptPanel.isVisible());
        ActionListener confirmEsc = qaPanel.userInputPromptPanel
                .getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        assertNotNull(confirmEsc);
        SwingTestUtils
                .runOnEdt(() -> confirmEsc.actionPerformed(new ActionEvent(qaPanel.userInputPromptPanel, 0, "esc")));
        assertEquals(Boolean.FALSE, escapeConfirmFuture.get(5, TimeUnit.SECONDS));
        assertFalse(qaPanel.userInputPromptPanel.isVisible());

        // 2. SINGLE_SELECT prompt on taskPanel
        CompletableFuture<Object> singleFuture = new CompletableFuture<>();
        taskPanel.controller.onUserInputRequest(
                new UserInputRequestEvent("task:default", UserInputRequestEvent.PromptType.SINGLE_SELECT,
                        "Select build tool:", null, null, List.of("Maven", "Gradle"), singleFuture));
        flushEdt();
        assertTrue(taskPanel.userInputPromptPanel.isVisible());

        JComboBox<?> comboBox = SwingTestUtils.findComponent(taskPanel.userInputPromptPanel, JComboBox.class);
        assertNotNull(comboBox);
        SwingTestUtils.runOnEdt(() -> comboBox.setSelectedItem("Gradle"));
        JButton submitBtn = SwingTestUtils.findButton(taskPanel.userInputPromptPanel, "Submit");
        assertNotNull(submitBtn);
        SwingTestUtils.clickOnEdt(submitBtn);
        assertEquals("Gradle", singleFuture.get(5, TimeUnit.SECONDS));
        assertFalse(taskPanel.userInputPromptPanel.isVisible());

        // 3. MULTI_SELECT prompt on taskPanel
        CompletableFuture<Object> multiFuture = new CompletableFuture<>();
        taskPanel.controller.onUserInputRequest(
                new UserInputRequestEvent("task:default", UserInputRequestEvent.PromptType.MULTI_SELECT,
                        "Select modules:", null, null, List.of("core", "ui", "api"), multiFuture));
        flushEdt();
        assertTrue(taskPanel.userInputPromptPanel.isVisible());

        JCheckBox coreBox = SwingTestUtils.findComponent(taskPanel.userInputPromptPanel, JCheckBox.class,
                cb -> "core".equals(cb.getText()));
        JCheckBox apiBox = SwingTestUtils.findComponent(taskPanel.userInputPromptPanel, JCheckBox.class,
                cb -> "api".equals(cb.getText()));
        assertNotNull(coreBox);
        assertNotNull(apiBox);
        SwingTestUtils.clickOnEdt(coreBox);
        SwingTestUtils.clickOnEdt(apiBox);
        JButton multiSubmit = SwingTestUtils.findButton(taskPanel.userInputPromptPanel, "Submit");
        SwingTestUtils.clickOnEdt(multiSubmit);
        assertEquals(List.of("core", "api"), multiFuture.get(5, TimeUnit.SECONDS));
        assertFalse(taskPanel.userInputPromptPanel.isVisible());

        // 4. TEXT prompt on qaPanel
        CompletableFuture<Object> textFuture = new CompletableFuture<>();
        qaPanel.controller.onUserInputRequest(new UserInputRequestEvent("qa:default",
                UserInputRequestEvent.PromptType.TEXT, "Target branch?", null, "main", List.of(), textFuture));
        flushEdt();
        assertTrue(qaPanel.userInputPromptPanel.isVisible());

        JTextField textField = SwingTestUtils.findComponent(qaPanel.userInputPromptPanel, JTextField.class);
        assertNotNull(textField);
        assertEquals("main", textField.getText());
        SwingTestUtils.runOnEdt(() -> textField.setText("feature/user-input"));
        JButton textSubmit = SwingTestUtils.findButton(qaPanel.userInputPromptPanel, "Submit");
        SwingTestUtils.clickOnEdt(textSubmit);
        assertEquals("feature/user-input", textFuture.get(5, TimeUnit.SECONDS));
        assertFalse(qaPanel.userInputPromptPanel.isVisible());

        // 5. Cancellation on stopTurn() and session reset
        CompletableFuture<Object> stopFuture = new CompletableFuture<>();
        taskPanel.controller.onUserInputRequest(new UserInputRequestEvent("task:default",
                UserInputRequestEvent.PromptType.CONFIRM, "Confirm before stop?", null, null, List.of(), stopFuture));
        flushEdt();
        assertTrue(taskPanel.userInputPromptPanel.isVisible());

        taskPanel.controller.stopTurn();
        flushEdt();
        assertTrue(stopFuture.isCancelled());
        assertFalse(taskPanel.userInputPromptPanel.isVisible());

        CompletableFuture<Object> resetFuture = new CompletableFuture<>();
        taskPanel.controller.onUserInputRequest(new UserInputRequestEvent("task:default",
                UserInputRequestEvent.PromptType.CONFIRM, "Confirm before reset?", null, null, List.of(), resetFuture));
        flushEdt();
        assertTrue(taskPanel.userInputPromptPanel.isVisible());

        taskPanel.controller.performResetSession();
        flushEdt();
        assertTrue(resetFuture.isCancelled());
        assertFalse(taskPanel.userInputPromptPanel.isVisible());
    }
}
