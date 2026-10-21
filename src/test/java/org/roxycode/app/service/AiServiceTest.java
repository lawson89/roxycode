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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.ReturnDirectToolCallingManager;
import org.roxycode.app.ai.RoxyToolExecutionExceptionProcessor;
import org.roxycode.app.ai.TurnToolContext;
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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
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

    @Mock private ToolCallingManager toolCallingManager;

    @Mock private ApplicationEventPublisher eventPublisher;

    private JexlTool jexlTool;
    private AiService aiService;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        lenient().when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);

        lenient()
                .when(
                        chatClientBuilder.defaultAdvisors(
                                any(org.springframework.ai.chat.client.advisor.api.Advisor[].class)))
                .thenReturn(chatClientBuilder);
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);

        jexlTool = new JexlTool(jexlServiceRegistry, eventPublisher);
        aiService =
                new AiService(
                        chatClientBuilder,
                        settingsService,
                        promptService,
                        jexlTool,
                        planService,
                        chatMemory,
                        toolCallingManager,
                        eventPublisher);
    }

    @Test
    void testAiConfigCreatesToolExecutionExceptionProcessorAndToolCallingManagerBeans() {
        AiConfig config = new AiConfig();
        ToolExecutionExceptionProcessor processor = config.toolExecutionExceptionProcessor();
        assertInstanceOf(RoxyToolExecutionExceptionProcessor.class, processor);

        ToolCallingManager manager =
                config.toolCallingManager(processor, settingsService, planService, eventPublisher);
        assertInstanceOf(ReturnDirectToolCallingManager.class, manager);
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
        verify(requestSpec).tools(jexlTool, planService);
        verify(requestSpec)
                .toolContext(
                        argThat(
                                ctx ->
                                        "default"
                                                        .equals(
                                                                ctx.get(
                                                                        TurnToolContext
                                                                                .CONVERSATION_ID_KEY))
                                                && WorkflowPhase.CODE
                                                        == ctx.get(TurnToolContext.PHASE_KEY)
                                                && ctx.get(TurnToolContext.TURN_COUNTER_KEY)
                                                        instanceof AtomicInteger));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatRegistersOnlyJexlToolInExplorePhase() {
        when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE, null))
                .thenReturn("EXPLORE SYSTEM PROMPT");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Explore Response");

        String result =
                aiService.chat("qa:default", WorkflowPhase.EXPLORE, "Inspect repo", null, null);

        assertEquals("Explore Response", result);
        verify(requestSpec).tools(jexlTool);
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
        verify(requestSpec).tools(jexlTool, planService);

        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).advisors(advisorCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        advisorCaptor.getValue().accept(advisorSpec);
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "custom-conv");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatHandlesTurnLimitExceeded() {
        settings.setMaxAgentToolTurns(2);

        AtomicReference<Map<String, Object>> capturedToolContext = new AtomicReference<>();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
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
        ToolCallingManager manager =
                new ReturnDirectToolCallingManager(
                        delegate, settingsService, planService, eventPublisher);

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
                            String out =
                                    jexlTool.executeJexl(
                                            "1 + 1", new ToolContext(capturedToolContext.get()));
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
        // Delegate is only invoked 2 times because the 3rd call short-circuits in the turn manager
        verify(delegate, times(2)).executeToolCalls(any(Prompt.class), eq(chatResponse));
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
    void testReturnDirectToolCallingManagerHandlesFailedTransitionAndLogsPlanToolCalls() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolCallingManager manager =
                new ReturnDirectToolCallingManager(
                        delegate, settingsService, planService, eventPublisher);

        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolContext(
                                new TurnToolContext(
                                                "task:default",
                                                WorkflowPhase.PLAN,
                                                new AtomicInteger(0))
                                        .toMap())
                        .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("Transition")), options);
        AssistantMessage.ToolCall tc =
                new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "requestDevelopment",
                        """
                        {"reason":"Ready"}""");
        AssistantMessage assistantMsg =
                AssistantMessage.builder().content("").toolCalls(List.of(tc)).build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMsg)));

        // Case 1: Transition tool fails validation (processed by
        // RoxyToolExecutionExceptionProcessor
        // into "Error: ...") -> returnDirect becomes false so agent can self-correct
        doReturn(
                        ToolExecutionResult.builder()
                                .conversationHistory(
                                        List.of(
                                                new UserMessage("Transition"),
                                                assistantMsg,
                                                ToolResponseMessage.builder()
                                                        .responses(
                                                                List.of(
                                                                        new ToolResponseMessage
                                                                                .ToolResponse(
                                                                                "call-1",
                                                                                "requestDevelopment",
                                                                                "Error: Cannot advance to CODE. Implementation Plan is missing.")))
                                                        .build()))
                                .returnDirect(true)
                                .build())
                .when(delegate)
                .executeToolCalls(prompt, chatResponse);

        ToolExecutionResult failedResult = manager.executeToolCalls(prompt, chatResponse);
        assertFalse(
                failedResult.returnDirect(),
                "Failed transition validation must return returnDirect=false so agent can self-correct");

        // Case 2: Valid transition tool with returnDirect=true -> preserves returnDirect=true and
        // unquotes JSON string
        String jsonQuoted = '"' + "PLAN SUMMARY: Transition Request" + '"';
        doReturn(
                        ToolExecutionResult.builder()
                                .conversationHistory(
                                        List.of(
                                                new UserMessage("Transition"),
                                                assistantMsg,
                                                ToolResponseMessage.builder()
                                                        .responses(
                                                                List.of(
                                                                        new ToolResponseMessage
                                                                                .ToolResponse(
                                                                                "call-1",
                                                                                "requestDevelopment",
                                                                                jsonQuoted)))
                                                        .build()))
                                .returnDirect(true)
                                .build())
                .when(delegate)
                .executeToolCalls(prompt, chatResponse);

        ToolExecutionResult successResult = manager.executeToolCalls(prompt, chatResponse);
        assertTrue(
                successResult.returnDirect(),
                "Valid transition tool should keep returnDirect=true");
        ToolResponseMessage lastMsg =
                assertInstanceOf(
                        ToolResponseMessage.class,
                        successResult
                                .conversationHistory()
                                .get(successResult.conversationHistory().size() - 1));
        assertEquals(
                "PLAN SUMMARY: Transition Request", lastMsg.getResponses().get(0).responseData());
        verify(eventPublisher, atLeast(2)).publishEvent(any(ToolExecutionEvent.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatPropagatesCancellationAndPostCallInterruption() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
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
