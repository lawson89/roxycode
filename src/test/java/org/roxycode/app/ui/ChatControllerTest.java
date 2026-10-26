package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserMessageEvent;

class ChatControllerTest extends ChatTestFixture {

    @Test
    void testRejectPhaseNotifiesWorkflowAndTriggersTurn() throws Exception {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenReturn("Let's revise the plan.");

        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);

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
    void testPerformResetSessionResetsWorkflowAndClearsMemory() throws Exception {
        FeatureChatPanel panel = createFeatureChatPanel(null);

        panel.getModel().setCurrentTurn(3);
        panel.getController().performResetSession();
        SwingUtilities.invokeAndWait(() -> {});

        verify(planService).resetWorkflow();
        verify(chatMemory).clear("task:default");
        assertEquals(0, panel.getModel().getCurrentTurn());
        assertEquals(0L, panel.getController().getActiveTurnId());
        assertTrue(panel.getOutputArea().getText().contains("Session reset."));
    }

    @Test
    void testOnToolExecutionRendersSuccessAndErrorStatus() throws Exception {
        FeatureChatPanel panel = createFeatureChatPanel(null);

        panel.onToolExecution(new ToolExecutionEvent("task:default", "1 + 1", 2, true, null));
        panel.onToolExecution(
                new ToolExecutionEvent("task:default", "if (", null, false, "Syntax Error: <bad>"));
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
        ExploreChatPanel panel = createExploreChatPanel(directExecutor);

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

        ExploreChatPanel panel = createExploreChatPanel(executorService);
        assertFalse(
                panel.getController().ownsTurnExecutor(),
                "Controller should not own externally supplied ExecutorService");

        JPanel host = new JPanel();
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
        ExploreChatPanel panel = createExploreChatPanel(directExecutor);

        panel.getModel().addPendingImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
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

    @Test
    void testTransientErrorWhenCurrentTurnPositiveAutoNudgesWhileTurnZeroDoesNot()
            throws Exception {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);

        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);
        panel.getController().setErrorNudgeDelayMs(0);

        when(aiService.chat(anyString(), any(), eq("Bad API call"), any()))
                .thenThrow(new RuntimeException("Failed to generate content"));

        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage("Bad API call"));

        verify(aiService, times(1)).chat(anyString(), any(), anyString(), any());
        assertEquals(0, panel.getController().getConsecutiveAutoNudges());
        assertFalse(panel.getOutputArea().getText().contains("Auto-nudging Roxy to continue"));
        assertFalse(panel.getModel().isLoading());

        clearInvocations(aiService);
        AtomicInteger callCount = new AtomicInteger(0);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            int n = callCount.incrementAndGet();
                            if (n == 1) {
                                panel.onAgentTurn(
                                        new AgentTurnEvent("task:default", "Roxy", 1, "CODE"));
                                throw new RuntimeException("Failed to generate content");
                            }
                            return "Recovered and completed.";
                        });

        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage("Implement step"));

        assertEquals(2, callCount.get());
        verify(aiService)
                .chat(eq("task:default"), eq(WorkflowPhase.CODE), eq("Implement step"), any());
        verify(aiService)
                .chat(
                        eq("task:default"),
                        eq(WorkflowPhase.CODE),
                        eq(ChatController.AUTO_NUDGE_PROMPT),
                        any());
        assertEquals(1, panel.getController().getConsecutiveAutoNudges());
        String html = panel.getOutputArea().getText();
        assertTrue(html.contains("Error: Failed to generate content"));
        assertTrue(html.contains("Auto-nudging Roxy to continue (1/2)..."));
        assertTrue(html.contains("Recovered and completed."));
        assertFalse(panel.getModel().isLoading());
    }

    @Test
    void testMaxToolTurnsTruncatedAndEmptyPostToolResponseAutoNudgeUpToCapAndResetOnUserAction()
            throws Exception {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);

        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);

        AtomicInteger callCount = new AtomicInteger(0);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            int n = callCount.incrementAndGet();
                            if (n == 1) {
                                return "Autonomous execution stopped: Maximum tool turns (5) exceeded.";
                            }
                            if (n == 2) {
                                return """
                                        Partial output

                                        ---
                                        > ⚠️ **Response Truncated (finishReason: MAX_TOKENS)**: The response was cut off.""";
                            }
                            return "Autonomous execution stopped: Maximum tool turns (5) exceeded.";
                        });

        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage("Run long task"));

        assertEquals(3, callCount.get());
        assertEquals(
                ChatController.MAX_AUTO_NUDGES, panel.getController().getConsecutiveAutoNudges());
        String html = panel.getOutputArea().getText();
        assertTrue(html.contains("Auto-nudging Roxy to continue (1/2)..."));
        assertTrue(html.contains("Auto-nudging Roxy to continue (2/2)..."));
        assertFalse(html.contains("Auto-nudging Roxy to continue (3/2)..."));
        assertFalse(panel.getModel().isLoading());

        AtomicInteger secondBatchCalls = new AtomicInteger(0);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            int n = secondBatchCalls.incrementAndGet();
                            if (n == 1) {
                                panel.onAgentTurn(
                                        new AgentTurnEvent("task:default", "Roxy", 2, "CODE"));
                                return "⚠️ **Empty Response Returned (finishReason: STOP)** "
                                        + "Roxy completed the turn without generating a visible text message.";
                            }
                            return "Final summary after empty response nudge.";
                        });

        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage("Next user message"));
        assertTrue(
                panel.getOutputArea()
                        .getText()
                        .contains("Final summary after empty response nudge."));
    }

    @Test
    void testStopTurnAndPhaseActionsCancelPendingAutoNudgeTimerAndResetCounter() throws Exception {
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);

        FeatureChatPanel panel = createFeatureChatPanel(directExecutor);
        panel.getController().setErrorNudgeDelayMs(5000);

        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            panel.onAgentTurn(
                                    new AgentTurnEvent("task:default", "Roxy", 1, "CODE"));
                            throw new RuntimeException("Failed to generate content");
                        });

        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage("Start work"));

        assertEquals(1, panel.getController().getConsecutiveAutoNudges());
        assertNotNull(panel.getController().getAutoNudgeTimer());
        assertTrue(panel.getController().isTurnActive());
        assertTrue(panel.getModel().isLoading());
        assertTrue(panel.getStopButton().isEnabled());

        SwingUtilities.invokeAndWait(() -> panel.getController().stopTurn());

        assertEquals(0, panel.getController().getConsecutiveAutoNudges());
        assertNull(panel.getController().getAutoNudgeTimer());
        assertFalse(panel.getController().isTurnActive());
        assertFalse(panel.getModel().isLoading());
        assertTrue(panel.getOutputArea().getText().contains("Request cancelled by user."));
        verify(aiService, times(1)).chat(anyString(), any(), anyString(), any());

        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.REVIEW);
        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage("Try with pending"));
        assertEquals(0, panel.getController().getConsecutiveAutoNudges());
        assertNull(panel.getController().getAutoNudgeTimer());
    }
}
