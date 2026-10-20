package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class ChatControllerTest {

    @Test
    void testRejectPhaseNotifiesWorkflowAndTriggersTurn() throws Exception {
        AiService aiService = mock(AiService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
        ChatMemory chatMemory = mock(ChatMemory.class);
        SettingsService settingsService = mock(SettingsService.class);
        PlanService planService = mock(PlanService.class);

        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(workflowService.getPendingPhase()).thenReturn(WorkflowPhase.CODE);
        when(aiService.chat(anyString(), any(), anyString(), any(), any()))
                .thenReturn("Let's revise the plan.");

        ChatPanel panel =
                new ChatPanel(
                        aiService,
                        workflowService,
                        turnEventBridge,
                        chatMemory,
                        "task",
                        null,
                        true,
                        settingsService,
                        planService);

        panel.getController().rejectPhase();
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {});

        verify(workflowService).rejectTransition();
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
        WorkflowService workflowService = mock(WorkflowService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel panel =
                new ChatPanel(
                        aiService,
                        workflowService,
                        turnEventBridge,
                        chatMemory,
                        "task",
                        null,
                        true);

        panel.getModel().setCurrentTurn(3);
        panel.getController().performResetSession();

        verify(workflowService).resetWorkflow();
        verify(chatMemory).clear("task:default");
        assertEquals(0, panel.getModel().getCurrentTurn());
        assertTrue(panel.getOutputArea().getText().contains("Session reset."));
    }

    @Test
    void testOnToolExecutionRendersSuccessAndErrorStatus() throws Exception {
        AiService aiService = mock(AiService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge turnEventBridge = new TurnEventBridge(publisher);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel panel =
                new ChatPanel(
                        aiService,
                        workflowService,
                        turnEventBridge,
                        chatMemory,
                        "task",
                        null,
                        true);

        panel.getController()
                .onToolExecution(
                        new org.roxycode.app.events.ToolExecutionEvent(
                                "task:default", "1 + 1", 2, true, null));
        panel.getController()
                .onToolExecution(
                        new org.roxycode.app.events.ToolExecutionEvent(
                                "task:default", "if (", null, false, "Syntax Error: <bad>"));
        SwingUtilities.invokeAndWait(() -> {});

        String html = panel.getOutputArea().getText();
        assertTrue(html.contains("Executed successfully"));
        assertTrue(html.contains("Error: Syntax Error: &lt;bad&gt;"));
        assertFalse(html.contains("<bad>"));
    }
}
