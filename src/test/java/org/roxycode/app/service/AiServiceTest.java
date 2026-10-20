package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;

    @Mock private ChatClient chatClient;

    @Mock private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock private ChatClient.CallResponseSpec responseSpec;

    @Mock private SettingsService settingsService;

    @Mock private PromptService promptService;

    @Mock private JexlServiceRegistry jexlServiceRegistry;

    @Mock private PlanService planService;

    @Mock private ChatMemory chatMemory;

    @Mock private ApplicationEventPublisher eventPublisher;

    private AiService aiService;
    private AppSettings settings;

    @AgentService(
            value = "transitionTestService",
            phases = {"*"})
    public static class TransitionTestService {
        @AgentDoc("Triggers a transition result.")
        public PlanService.TransitionResult triggerTransition() {
            return new PlanService.TransitionResult(
                    """
                    PLAN SUMMARY

                    Transition Request""");
        }
    }

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        lenient().when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);

        when(chatClientBuilder.defaultAdvisors(
                        any(org.springframework.ai.chat.client.advisor.api.Advisor[].class)))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        aiService =
                new AiService(
                        chatClientBuilder,
                        settingsService,
                        promptService,
                        jexlServiceRegistry,
                        planService,
                        chatMemory,
                        eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatUsesConfiguredModelAndDynamicPrompt() {
        settings.setGeminiModel("test-model-123");
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(promptService.buildSystemPrompt(WorkflowPhase.CODE, null))
                .thenReturn("FULL CODE SYSTEM PROMPT");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);

        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("AI Response");

        String result = aiService.chat("Hello");

        assertEquals("AI Response", result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        String capturedPrompt = systemPromptCaptor.getValue();

        assertEquals("FULL CODE SYSTEM PROMPT", capturedPrompt);
        verify(promptService).buildSystemPrompt(WorkflowPhase.CODE, null);
        verify(requestSpec).options(any());
        verify(requestSpec)
                .toolContext(argThat(ctx -> ctx.containsKey(AiService.TOOL_CONTEXT_JEXL_TOOL)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatWithConversationIdAndPhase() {
        settings.setGeminiModel("test-model-123");
        when(promptService.buildSystemPrompt(WorkflowPhase.REVIEW, "Extra instructions"))
                .thenReturn("REVIEW SYSTEM PROMPT");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);

        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("AI Response");

        String result =
                aiService.chat(
                        "custom-conv", WorkflowPhase.REVIEW, "Hello", "Extra instructions", null);

        assertEquals("AI Response", result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        assertEquals("REVIEW SYSTEM PROMPT", systemPromptCaptor.getValue());

        // Verify conversation ID set in advisor
        verify(requestSpec).advisors(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatHandlesTurnLimitExceeded() {
        settings.setMaxAgentToolTurns(2);

        AtomicReference<JexlTool> capturedTool = new AtomicReference<>();
        AtomicReference<Map<String, Object>> capturedToolContext = new AtomicReference<>();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any()))
                .thenAnswer(
                        inv -> {
                            capturedTool.set(inv.getArgument(0));
                            return requestSpec;
                        });
        when(requestSpec.toolContext(anyMap()))
                .thenAnswer(
                        inv -> {
                            capturedToolContext.set(inv.getArgument(0));
                            return requestSpec;
                        });
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE, null))
                .thenReturn("EXPLORE PROMPT");

        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolCallingManager manager = aiService.createReturnDirectToolCallingManager(delegate);

        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "executeJexl",
                        """
                        {"script":"1 + 1"}""");
        AssistantMessage assistantMessage =
                AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));

        when(delegate.executeToolCalls(any(Prompt.class), eq(chatResponse)))
                .thenAnswer(
                        inv -> {
                            String out = capturedTool.get().executeJexl("1 + 1");
                            ToolResponseMessage toolMsg =
                                    ToolResponseMessage.builder()
                                            .responses(
                                                    List.of(
                                                            new ToolResponseMessage.ToolResponse(
                                                                    "call-1", "executeJexl", out)))
                                            .build();
                            return ToolExecutionResult.builder()
                                    .conversationHistory(
                                            List.of(
                                                    new UserMessage("Hello"),
                                                    assistantMessage,
                                                    toolMsg))
                                    .returnDirect(false)
                                    .build();
                        });

        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content())
                .thenAnswer(
                        inv -> {
                            ToolCallingChatOptions options =
                                    ToolCallingChatOptions.builder()
                                            .toolContext(capturedToolContext.get())
                                            .build();
                            Prompt prompt = new Prompt(List.of(new UserMessage("Hello")), options);

                            ToolExecutionResult r1 = manager.executeToolCalls(prompt, chatResponse);
                            assertFalse(r1.returnDirect());
                            ToolExecutionResult r2 = manager.executeToolCalls(prompt, chatResponse);
                            assertFalse(r2.returnDirect());
                            // 3rd call exceeds maxTurns (2) and short-circuits with
                            // returnDirect(true)
                            ToolExecutionResult r3 = manager.executeToolCalls(prompt, chatResponse);
                            assertTrue(r3.returnDirect());

                            List<Message> history = r3.conversationHistory();
                            assertEquals(3, history.size());
                            assertEquals(assistantMessage, history.get(1));
                            ToolResponseMessage stopToolMsg =
                                    assertInstanceOf(ToolResponseMessage.class, history.get(2));
                            return stopToolMsg.getResponses().get(0).responseData();
                        });

        String result = aiService.chat("Hello");

        assertEquals("Autonomous execution stopped: Maximum tool turns (2) exceeded.", result);
        // Delegate is invoked on all 3 calls; JexlTool short-circuits on the 3rd call
        verify(delegate, times(3)).executeToolCalls(any(Prompt.class), eq(chatResponse));
        verify(eventPublisher, times(2)).publishEvent(any(AgentTurnEvent.class));
        verify(eventPublisher, times(2)).publishEvent(any(ToolExecutionEvent.class));

        ArgumentCaptor<AgentTurnCompleteEvent> completeCaptor =
                ArgumentCaptor.forClass(AgentTurnCompleteEvent.class);
        verify(eventPublisher).publishEvent(completeCaptor.capture());
        assertEquals(2, completeCaptor.getValue().totalTurns());
        assertEquals(
                "Autonomous execution stopped: Maximum tool turns (2) exceeded.",
                completeCaptor.getValue().result());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatFormatsDirectReturnTransitionOutput() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(promptService.buildSystemPrompt(WorkflowPhase.PLAN, null)).thenReturn("PLAN PROMPT");

        String expectedMarkdown =
                """
                PLAN SUMMARY

                Transition Request""";
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(expectedMarkdown);

        String result = aiService.chat("Create plan");

        assertEquals(expectedMarkdown, result);
    }

    @Test
    void testReturnDirectToolCallingManagerMarksTransitionAsReturnDirect() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolCallingManager manager = aiService.createReturnDirectToolCallingManager(delegate);

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TransitionTestService());
        JexlTool jexlTool = new JexlTool(registry, WorkflowPhase.PLAN);

        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolContext(Map.of(AiService.TOOL_CONTEXT_JEXL_TOOL, jexlTool))
                        .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("Transition")), options);
        ChatResponse chatResponse = mock(ChatResponse.class);

        when(delegate.executeToolCalls(prompt, chatResponse))
                .thenAnswer(
                        inv -> {
                            String out =
                                    jexlTool.executeJexl(
                                            "transitionTestService.triggerTransition()");
                            ToolResponseMessage transitionMsg =
                                    ToolResponseMessage.builder()
                                            .responses(
                                                    List.of(
                                                            new ToolResponseMessage.ToolResponse(
                                                                    "1", "executeJexl", out)))
                                            .build();
                            return ToolExecutionResult.builder()
                                    .conversationHistory(List.of(transitionMsg))
                                    .returnDirect(false)
                                    .build();
                        });

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        assertTrue(
                result.returnDirect(),
                "Transition tool response should set ToolExecutionResult.returnDirect() to true");

        when(delegate.executeToolCalls(prompt, chatResponse))
                .thenAnswer(
                        inv -> {
                            String out = jexlTool.executeJexl("1 + 1");
                            ToolResponseMessage normalMsg =
                                    ToolResponseMessage.builder()
                                            .responses(
                                                    List.of(
                                                            new ToolResponseMessage.ToolResponse(
                                                                    "2", "executeJexl", out)))
                                            .build();
                            return ToolExecutionResult.builder()
                                    .conversationHistory(List.of(normalMsg))
                                    .returnDirect(false)
                                    .build();
                        });

        ToolExecutionResult normalResult = manager.executeToolCalls(prompt, chatResponse);
        assertFalse(
                normalResult.returnDirect(),
                "Normal tool response should keep ToolExecutionResult.returnDirect() as false");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatPropagatesCancellationAndPostCallInterruption() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(promptService.buildSystemPrompt(WorkflowPhase.CODE, null)).thenReturn("CODE PROMPT");

        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content())
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            return "Should be discarded due to interrupt";
                        });

        try {
            assertThrows(CancellationException.class, () -> aiService.chat("Run code"));
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be preserved when chat is cancelled");
        } finally {
            Thread.interrupted();
        }
    }
}
