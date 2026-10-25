package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.event.ActionEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.service.AiService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

public class ChatPanelStopButtonTest {

    @Test
    public void testStopButtonEnablement() {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ChatPanel chatPanel =
                new ChatPanel(
                        aiService,
                        planService,
                        publisher,
                        chatMemory,
                        "test",
                        null,
                        true,
                        null,
                        null);
        JButton stopButton = chatPanel.getStopButton();
        JButton sendButton = chatPanel.getSendButton();
        JTextArea inputArea = chatPanel.getInputArea();

        assertFalse(stopButton.isEnabled(), "Stop button should be disabled initially");
        assertTrue(sendButton.isEnabled(), "Send button should be enabled initially");

        inputArea.setText("test message");

        for (java.awt.event.ActionListener al : sendButton.getActionListeners()) {
            al.actionPerformed(new ActionEvent(sendButton, ActionEvent.ACTION_PERFORMED, null));
        }

        assertTrue(stopButton.isEnabled(), "Stop button should be enabled while sending");
        assertFalse(sendButton.isEnabled(), "Send button should be disabled while sending");
        assertFalse(inputArea.isEnabled(), "Input area should be disabled while sending");
    }

    @Test
    public void testStopButtonCancelsTurnAndBlocksLateAutonomousTransitionsAndEvents()
            throws Exception {
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

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
                                throw new java.util.concurrent.CancellationException("Interrupted");
                            }
                            return "Late AI response";
                        });

        FeatureChatPanel chatPanel =
                new FeatureChatPanel(aiService, planService, publisher, chatMemory, null, null);

        chatPanel.getInputArea().setText("Start coding");
        chatPanel.getSendButton().doClick();

        assertTrue(
                callStarted.await(5, TimeUnit.SECONDS),
                "Background worker should start aiService.chat");

        // Simulate an event queued before Stop is clicked
        chatPanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Pre-stop transition", true));
        SwingUtilities.invokeAndWait(() -> {});

        // Click Stop on EDT
        SwingUtilities.invokeAndWait(() -> chatPanel.getStopButton().doClick());
        SwingUtilities.invokeAndWait(() -> {});

        // Now simulate late events from the dying background thread after Stop was
        // clicked
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
}
