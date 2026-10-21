package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
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

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        when(aiService.chat(anyString(), any(), anyString(), any(), any()))
                .thenReturn("Let's revise the plan.");

        FeatureChatPanel panel =
                new FeatureChatPanel(
                        aiService, planService, publisher, chatMemory, settingsService);

        panel.getController().rejectPhase();
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {});

        verify(planService).rejectTransition();
        verify(aiService)
                .chat(
                        eq("task:default"),
                        eq(WorkflowPhase.PLAN),
                        contains("I have rejected the transition to Code"),
                        isNull(),
                        any());
        assertTrue(panel.getOutputArea().getText().contains("Transition to"));
    }

    @Test
    void testPerformResetSessionResetsWorkflowAndClearsMemory() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel panel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null);

        panel.getModel().setCurrentTurn(3);
        panel.getController().performResetSession();

        verify(planService).resetWorkflow();
        verify(chatMemory).clear("task:default");
        assertEquals(0, panel.getModel().getCurrentTurn());
        assertTrue(panel.getOutputArea().getText().contains("Session reset."));
    }

    @Test
    void testOnToolExecutionRendersSuccessAndErrorStatus() throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        FeatureChatPanel panel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null);

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

        ExploreChatPanel panel =
                new ExploreChatPanel(aiService, planService, publisher, chatMemory, null);

        panel.getController().sendMessage("Hello mentor");

        verify(publisher).publishEvent(new UserMessageEvent("qa:default", "user", "Hello mentor"));
    }
}
