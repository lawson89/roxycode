package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
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
    @SuppressWarnings("unchecked")
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);

        lenient()
                .when(chatClientBuilder.defaultAdvisors(any(Advisor[].class)))
                .thenReturn(chatClientBuilder);
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);

        jexlTool = new JexlTool(jexlServiceRegistry, eventPublisher, null);
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

    private void stubChatResponse(
            WorkflowPhase phase,
            String promptText,
            String responseText,
            String finishReason,
            Usage usage) {
        lenient().when(planService.getCurrentPhase()).thenReturn(phase);
        lenient().when(promptService.buildSystemPrompt(phase, null)).thenReturn(promptText);
        lenient()
                .when(responseSpec.chatResponse())
                .thenReturn(buildChatResponse(responseText, finishReason, usage));
    }

    private static ChatResponse buildChatResponse(String text, String finishReason, Usage usage) {
        ChatGenerationMetadata genMeta =
                finishReason != null
                        ? ChatGenerationMetadata.builder().finishReason(finishReason).build()
                        : ChatGenerationMetadata.NULL;
        Generation generation = new Generation(new AssistantMessage(text), genMeta);
        ChatResponseMetadata responseMeta =
                usage != null
                        ? ChatResponseMetadata.builder().usage(usage).build()
                        : new ChatResponseMetadata();
        return new ChatResponse(List.of(generation), responseMeta);
    }

    @Test
    void testAiConfigCreatesToolExecutionExceptionProcessorAndToolCallingManagerBeans() {
        AiConfig config = new AiConfig();
        ToolExecutionExceptionProcessor processor = config.toolExecutionExceptionProcessor();
        assertInstanceOf(RoxyToolExecutionExceptionProcessor.class, processor);

        ToolCallingManager manager =
                config.toolCallingManager(
                        processor,
                        settingsService,
                        planService,
                        eventPublisher,
                        config.objectMapper());
        assertInstanceOf(ReturnDirectToolCallingManager.class, manager);
    }

    @Test
    void testChatUsesConfiguredModelAndDynamicPrompt() {
        settings.setGeminiModel("test-model-123");
        stubChatResponse(
                WorkflowPhase.CODE, "FULL CODE SYSTEM PROMPT", "AI Response", "STOP", null);

        String result = aiService.chat("default", null, "Hello", null);

        assertEquals("AI Response", result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        assertEquals("FULL CODE SYSTEM PROMPT", systemPromptCaptor.getValue());
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
    void testChatRegistersOnlyJexlToolInExplorePhase() {
        stubChatResponse(
                WorkflowPhase.EXPLORE, "EXPLORE SYSTEM PROMPT", "Explore Response", "STOP", null);

        String result = aiService.chat("qa:default", WorkflowPhase.EXPLORE, "Inspect repo", null);

        assertEquals("Explore Response", result);
        verify(requestSpec).tools(jexlTool);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatWithConversationIdAndPhase() {
        settings.setGeminiModel("test-model-123");
        when(promptService.buildSystemPrompt(WorkflowPhase.REVIEW, "Extra instructions"))
                .thenReturn("REVIEW SYSTEM PROMPT");
        when(responseSpec.chatResponse())
                .thenReturn(buildChatResponse("AI Response", "STOP", null));

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
    void testChatPublishesTurnCompleteEventWithFinalTurnCount() {
        AtomicReference<Map<String, Object>> capturedToolContext = new AtomicReference<>();
        when(requestSpec.toolContext(anyMap()))
                .thenAnswer(
                        inv -> {
                            capturedToolContext.set(inv.getArgument(0));
                            return requestSpec;
                        });
        stubChatResponse(WorkflowPhase.EXPLORE, "EXPLORE PROMPT", "Done", "STOP", null);
        when(responseSpec.chatResponse())
                .thenAnswer(
                        inv -> {
                            AtomicInteger counter =
                                    (AtomicInteger)
                                            capturedToolContext
                                                    .get()
                                                    .get(TurnToolContext.TURN_COUNTER_KEY);
                            counter.set(2);
                            return buildChatResponse(
                                    "Autonomous execution stopped: Maximum tool turns (2) exceeded.",
                                    "STOP",
                                    null);
                        });

        String result = aiService.chat("default", null, "Hello", null);

        assertEquals("Autonomous execution stopped: Maximum tool turns (2) exceeded.", result);
        ArgumentCaptor<AgentTurnCompleteEvent> completeCaptor =
                ArgumentCaptor.forClass(AgentTurnCompleteEvent.class);
        verify(eventPublisher).publishEvent(completeCaptor.capture());
        assertEquals(2, completeCaptor.getValue().totalTurns());
        assertEquals(result, completeCaptor.getValue().result());
    }

    @Test
    void testChatFormatsDirectReturnTransitionOutput() {
        String expectedMarkdown =
                """
                PLAN SUMMARY

                Transition Request""";
        stubChatResponse(WorkflowPhase.PLAN, "PLAN PROMPT", expectedMarkdown, "STOP", null);

        String result = aiService.chat("default", null, "Create plan", null);

        assertEquals(expectedMarkdown, result);
    }

    static Stream<Arguments> finishReasonFormattingCases() {
        return Stream.of(
                Arguments.of(
                        "Complete answer.",
                        "STOP",
                        new DefaultUsage(100, 50, 150),
                        List.of("Complete answer.")),
                Arguments.of(
                        "Partial output here...",
                        "MAX_TOKENS",
                        new DefaultUsage(1200, 8192, 9392),
                        List.of(
                                "Partial output here...",
                                "MAX_TOKENS",
                                "promptTokens=1200, completionTokens=8192, totalTokens=9392",
                                "continue")),
                Arguments.of(
                        "   ",
                        "MAX_TOKENS",
                        new DefaultUsage(4000, 8192, 12192),
                        List.of(
                                "MAX_TOKENS",
                                "promptTokens=4000, completionTokens=8192, totalTokens=12192",
                                "continue")),
                Arguments.of(
                        null,
                        "MALFORMED_FUNCTION_CALL",
                        null,
                        List.of("MALFORMED_FUNCTION_CALL", "blocked or aborted")),
                Arguments.of("", "SAFETY", null, List.of("SAFETY", "blocked or aborted")));
    }

    @ParameterizedTest
    @MethodSource("finishReasonFormattingCases")
    void testChatResponseFormattingByFinishReason(
            String rawText, String finishReason, Usage usage, List<String> expectedSnippets) {
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", rawText, finishReason, usage);

        String result = aiService.chat("default", null, "Prompt", null);
        String directFormatted =
                aiService.formatChatResponse(
                        buildChatResponse(rawText, finishReason, usage),
                        new TurnToolContext(
                                "default", WorkflowPhase.CODE, new AtomicInteger(0), null));

        assertEquals(directFormatted, result);
        for (String snippet : expectedSnippets) {
            assertTrue(
                    result.toLowerCase().contains(snippet.toLowerCase()),
                    "Expected response to contain '" + snippet + "' but got: " + result);
        }
        verify(requestSpec, times(1)).call();
        ArgumentCaptor<AgentTurnCompleteEvent> completeCaptor =
                ArgumentCaptor.forClass(AgentTurnCompleteEvent.class);
        verify(eventPublisher).publishEvent(completeCaptor.capture());
        assertEquals(result, completeCaptor.getValue().result());
    }

    @Test
    void testBlankResponseAfterToolExecutionWithStopReturnsDiagnosticMessage() {
        AtomicReference<Map<String, Object>> capturedToolContext = new AtomicReference<>();
        when(requestSpec.toolContext(anyMap()))
                .thenAnswer(
                        inv -> {
                            capturedToolContext.set(inv.getArgument(0));
                            return requestSpec;
                        });
        stubChatResponse(WorkflowPhase.EXPLORE, "EXPLORE PROMPT", "", "STOP", null);

        Usage usage = new DefaultUsage(2500, 300, 2800);
        when(responseSpec.chatResponse())
                .thenAnswer(
                        inv -> {
                            AtomicInteger counter =
                                    (AtomicInteger)
                                            capturedToolContext
                                                    .get()
                                                    .get(TurnToolContext.TURN_COUNTER_KEY);
                            counter.set(3);
                            return buildChatResponse("", "STOP", usage);
                        });

        String result = aiService.chat("default", null, "Analyze project", null);

        assertTrue(result.contains("STOP"));
        assertTrue(result.contains("3"));
        assertTrue(result.contains("promptTokens=2500, completionTokens=300, totalTokens=2800"));
        assertTrue(result.toLowerCase().contains("continue"));
        verify(requestSpec, times(1)).call();
    }

    @Test
    void testChatPropagatesCancellationAndPostCallInterruption() {
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);
        when(responseSpec.chatResponse())
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            return buildChatResponse(
                                    "Should be discarded due to interrupt", "STOP", null);
                        });

        try {
            assertThrows(
                    CancellationException.class,
                    () -> aiService.chat("default", null, "Run code", null));
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be preserved when chat is cancelled");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testChatExceptionRethrowsWithoutPublishingTurnCompleteEvent() {
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);
        when(requestSpec.call()).thenThrow(new IllegalStateException("API failure"));

        assertThrows(
                IllegalStateException.class,
                () -> aiService.chat("default", null, "Trigger error", null));
        verify(eventPublisher, never()).publishEvent(any(AgentTurnCompleteEvent.class));
    }
}
