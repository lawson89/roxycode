package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserMessageEvent;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class ChatControllerTest {

    @Test
    void testRejectPhaseNotifiesWorkflowAndTriggersTurn() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        SettingsService settingsService = mock(SettingsService.class);
        Executor directExecutor = Runnable::run;

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenReturn("Let's revise the plan.");

        FeatureChatPanel panel =
                new FeatureChatPanel(
                        aiService,
                        planService,
                        publisher,
                        chatMemory,
                        settingsService,
                        directExecutor);

        assertSame(directExecutor, panel.getController().getTurnExecutor());

        panel.getController().rejectPhase();
        SwingUtilities.invokeAndWait(() -> {});

        verify(planService).rejectTransition();
        verify(aiService)
                .chat(
                        eq("task:default"),
                        eq(WorkflowPhase.PLAN),
                        contains("I have rejected the transition to Code"),
                        any());
        assertEquals(0L, panel.getController().getActiveTurnId());
        assertTrue(panel.getOutputArea().getText().contains("Transition to"));
    }

    @Test
    void testPerformResetSessionResetsWorkflowAndClearsMemory() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel panel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        panel.getModel().setCurrentTurn(3);
        panel.getController().performResetSession();

        verify(planService).resetWorkflow();
        verify(chatMemory).clear("task:default");
        assertEquals(0, panel.getModel().getCurrentTurn());
        assertEquals(0L, panel.getController().getActiveTurnId());
        assertTrue(panel.getOutputArea().getText().contains("Session reset."));
    }

    @Test
    void testOnToolExecutionRendersSuccessAndErrorStatus() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel panel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        panel.onToolExecution(new ToolExecutionEvent("task:default", "1 + 1", 2, true, null));
        panel.onToolExecution(
                new ToolExecutionEvent("task:default", "if (", null, false, "Syntax Error: <bad>"));
        // Event for another conversation should be ignored
        panel.onToolExecution(
                new ToolExecutionEvent("qa:default", "ignoredCall()", null, true, null));
        SwingUtilities.invokeAndWait(() -> {});

        String html = panel.getOutputArea().getText();
        assertTrue(html.contains("Executed successfully"));
        assertTrue(html.contains("Error: Syntax Error: &lt;bad&gt;"));
        assertFalse(html.contains("<bad>"));
        assertFalse(html.contains("ignoredCall"));
    }

    @Test
    void testSendMessagePublishesUserMessageEvent() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        Executor directExecutor = Runnable::run;

        ExploreChatPanel panel =
                new ExploreChatPanel(
                        aiService, planService, publisher, chatMemory, null, directExecutor);

        assertSame(directExecutor, panel.getController().getTurnExecutor());

        panel.getController().sendMessage("Hello mentor");

        verify(publisher).publishEvent(new UserMessageEvent("qa:default", "user", "Hello mentor"));
    }

    @Test
    void testCloseAndManagedChatTurnExecutorLifecycle() {
        AiConfig config = new AiConfig();
        ExecutorService executorService = config.chatTurnExecutor();
        assertNotNull(executorService);
        assertFalse(executorService.isShutdown());

        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ExploreChatPanel panel =
                new ExploreChatPanel(
                        aiService, planService, publisher, chatMemory, null, executorService);
        assertFalse(
                panel.getController().ownsTurnExecutor(),
                "Controller should not own externally supplied ExecutorService");

        // Removing panel from container must not shut down shared external executor
        javax.swing.JPanel host = new javax.swing.JPanel();
        host.add(panel);
        host.remove(panel);
        panel.removeNotify();
        assertFalse(
                executorService.isShutdown(),
                "Shared external ExecutorService must not be shut down on removeNotify()");

        panel.close();
        assertTrue(executorService.isShutdown());
    }

    @Test
    void testFallbackDefaultExecutorOwnedAndClosedOnRemoveNotify() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel panel =
                new ChatPanel(
                        aiService,
                        planService,
                        publisher,
                        chatMemory,
                        ChatModel.QA_SESSION_PREFIX,
                        WorkflowPhase.EXPLORE,
                        false,
                        null,
                        null);

        assertTrue(
                panel.getController().ownsTurnExecutor(),
                "Convenience constructor should mark fallback executor as owned");
        ExecutorService fallbackExecutor =
                assertInstanceOf(ExecutorService.class, panel.getController().getTurnExecutor());
        assertFalse(fallbackExecutor.isShutdown());

        panel.removeNotify();
        assertTrue(
                fallbackExecutor.isShutdown(),
                "Fallback virtual-thread executor must be shut down when panel is removed/disposed");
    }
    @Test
    void testSendMessageWithEmptyTextAndPendingImageShowsFeedbackAndPreservesImage() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        Executor directExecutor = Runnable::run;

        ExploreChatPanel panel =
                new ExploreChatPanel(
                        aiService, planService, publisher, chatMemory, null, directExecutor);

        panel.getModel()
                .addPendingImage(
                        new java.awt.image.BufferedImage(
                                8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB));
        assertTrue(panel.getModel().hasPendingImages());

        panel.getController().sendMessage("   ");

        assertTrue(
                panel.getModel().hasPendingImages(),
                "Pending image must be preserved when prompt text is empty");
        assertTrue(
                panel.getOutputArea()
                        .getText()
                        .contains(ChatController.IMAGE_REQUIRES_TEXT_MESSAGE),
                "System feedback message should be displayed when sending image without text");
        verifyNoInteractions(publisher);
        verifyNoInteractions(aiService);
    }
}
