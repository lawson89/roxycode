package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.ui.SwingTestUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec responseSpec;
    @Mock
    private SettingsService settingsService;
    @Mock
    private PromptService promptService;
    @Mock
    private JexlServiceRegistry jexlServiceRegistry;
    @Mock
    private PlanService planService;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private ToolCallingManager toolCallingManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        lenient().when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);
        lenient().when(chatClientBuilder.defaultAdvisors(any(Advisor[].class))).thenReturn(chatClientBuilder);
        lenient().when(chatClientBuilder.build()).thenReturn(chatClient);

        jexlTool = new JexlTool(jexlServiceRegistry, eventPublisher, null, settingsService);
        aiService = new AiService(chatClientBuilder, settingsService, promptService, jexlTool, planService, chatMemory,
                toolCallingManager, eventPublisher);
    }

    private void stubChatResponse(WorkflowPhase phase, String promptText, String responseText, String finishReason,
            Usage usage) {
        lenient().when(planService.getCurrentPhase()).thenReturn(phase);
        lenient().when(promptService.buildSystemPrompt(phase, null)).thenReturn(promptText);
        lenient().when(responseSpec.chatResponse()).thenReturn(buildChatResponse(responseText, finishReason, usage));
    }

    private static ChatResponse buildChatResponse(String text, String finishReason, Usage usage) {
        ChatGenerationMetadata genMeta = finishReason != null
                ? ChatGenerationMetadata.builder().finishReason(finishReason).build()
                : ChatGenerationMetadata.NULL;
        ChatResponseMetadata responseMeta = usage != null
                ? ChatResponseMetadata.builder().usage(usage).build()
                : new ChatResponseMetadata();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), genMeta)), responseMeta);
    }

    private void stubToolTurnsAndResponse(int turns, String responseText, String finishReason, Usage usage) {
        AtomicReference<Map<String, Object>> capturedToolContext = new AtomicReference<>();
        when(requestSpec.toolContext(anyMap())).thenAnswer(inv -> {
            capturedToolContext.set(inv.getArgument(0));
            return requestSpec;
        });
        stubChatResponse(WorkflowPhase.EXPLORE, "EXPLORE PROMPT", responseText, finishReason, null);
        when(responseSpec.chatResponse()).thenAnswer(inv -> {
            ((AtomicInteger) capturedToolContext.get().get(TurnToolContext.TURN_COUNTER_KEY)).set(turns);
            return buildChatResponse(responseText, finishReason, usage);
        });
    }

    private AgentTurnCompleteEvent captureTurnCompleteEvent() {
        ArgumentCaptor<AgentTurnCompleteEvent> captor = ArgumentCaptor.forClass(AgentTurnCompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void testAiConfigCreatesToolExecutionExceptionProcessorAndToolCallingManagerBeans() throws Exception {
        AiConfig config = new AiConfig();
        ToolExecutionExceptionProcessor processor = config.toolExecutionExceptionProcessor();
        assertNotNull(processor);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        String jsonError = processor
                .process(new ToolExecutionException(toolDef, new IllegalArgumentException("Invalid parameter")));
        assertEquals("""
                {"error":"Invalid parameter"}""", jsonError);
        assertEquals(Map.of("error", "Invalid parameter"),
                AiConfig.SHARED_OBJECT_MAPPER.readValue(jsonError, Map.class));
        String fallbackJsonError = processor
                .process(new ToolExecutionException(toolDef, new IllegalArgumentException("   ")));
        assertEquals(Map.of("error", "Tool execution failed"),
                AiConfig.SHARED_OBJECT_MAPPER.readValue(fallbackJsonError, Map.class));
        assertThrows(CancellationException.class, () -> processor
                .process(new ToolExecutionException(toolDef, new CancellationException("User stopped"))));
        assertNotNull(config.toolCallingManager(processor));
    }

    @Test
    void testChatUsesConfiguredModelAndDynamicPrompt() {
        settings.setGeminiModel("test-model-123");
        stubChatResponse(WorkflowPhase.CODE, "FULL CODE SYSTEM PROMPT", "AI Response", "STOP", null);

        assertEquals("AI Response", aiService.chat("default", null, "Hello", null));
        verify(requestSpec).system("FULL CODE SYSTEM PROMPT");
        verify(promptService).buildSystemPrompt(WorkflowPhase.CODE, null);
        verify(requestSpec).options(any());
        verify(requestSpec).tools(jexlTool);
        verify(requestSpec, times(1)).tools(any(Object[].class));
        verify(requestSpec).toolContext(argThat(ctx -> "default".equals(ctx.get(TurnToolContext.CONVERSATION_ID_KEY))
                && WorkflowPhase.CODE == ctx.get(TurnToolContext.PHASE_KEY)
                && ctx.get(TurnToolContext.TURN_COUNTER_KEY) instanceof AtomicInteger));
    }

    @Test
    void testChatRegistersOnlyJexlToolInExplorePhase() {
        stubChatResponse(WorkflowPhase.EXPLORE, "EXPLORE SYSTEM PROMPT", "Explore Response", "STOP", null);
        assertEquals("Explore Response", aiService.chat("qa:default", WorkflowPhase.EXPLORE, "Inspect repo", null));
        verify(requestSpec).tools(jexlTool);
        verify(requestSpec, times(1)).tools(any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatWithConversationIdAndPhase() {
        settings.setGeminiModel("test-model-123");
        when(promptService.buildSystemPrompt(WorkflowPhase.REVIEW, "Extra instructions"))
                .thenReturn("REVIEW SYSTEM PROMPT");
        when(responseSpec.chatResponse()).thenReturn(buildChatResponse("AI Response", "STOP", null));

        assertEquals("AI Response",
                aiService.chat("custom-conv", WorkflowPhase.REVIEW, "Hello", "Extra instructions", null));
        verify(requestSpec).system("REVIEW SYSTEM PROMPT");
        verify(requestSpec).tools(jexlTool);
        verify(requestSpec, times(1)).tools(any(Object[].class));

        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).advisors(advisorCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        advisorCaptor.getValue().accept(advisorSpec);
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "custom-conv");
    }

    @Test
    void testChatPublishesTurnCompleteEventWithFinalTurnCount() {
        String msg = "Autonomous execution stopped: Maximum tool turns (2) exceeded.";
        stubToolTurnsAndResponse(2, msg, "STOP", null);

        String result = aiService.chat("default", null, "Hello", null);
        assertEquals(msg, result);
        AgentTurnCompleteEvent ev = captureTurnCompleteEvent();
        assertEquals(2, ev.totalTurns());
        assertEquals(result, ev.result());
    }

    @Test
    void testChatFormatsDirectReturnTransitionOutput() {
        String expectedMarkdown = "PLAN SUMMARY\n\nTransition Request";
        stubChatResponse(WorkflowPhase.PLAN, "PLAN PROMPT", expectedMarkdown, "STOP", null);
        assertEquals(expectedMarkdown, aiService.chat("default", null, "Create plan", null));
    }

    private static Arguments caseArgs(String text, String reason, Usage usage, String... snippets) {
        return Arguments.of(text, reason, usage, List.of(snippets));
    }

    static Stream<Arguments> finishReasonFormattingCases() {
        return Stream.of(caseArgs("Complete answer.", "STOP", new DefaultUsage(100, 50, 150), "Complete answer."),
                caseArgs("Partial output here...", "MAX_TOKENS", new DefaultUsage(1200, 8192, 9392),
                        "Partial output here...", "MAX_TOKENS",
                        "promptTokens=1200, completionTokens=8192, totalTokens=9392", "continue"),
                caseArgs("   ", "MAX_TOKENS", new DefaultUsage(4000, 8192, 12192), "MAX_TOKENS",
                        "promptTokens=4000, completionTokens=8192, totalTokens=12192", "continue"),
                caseArgs(null, "MALFORMED_FUNCTION_CALL", null, "MALFORMED_FUNCTION_CALL", "blocked or aborted"),
                caseArgs("", "SAFETY", null, "SAFETY", "blocked or aborted"));
    }

    @ParameterizedTest
    @MethodSource("finishReasonFormattingCases")
    void testChatResponseFormattingByFinishReason(String rawText, String finishReason, Usage usage,
            List<String> expectedSnippets) {
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", rawText, finishReason, usage);

        String result = aiService.chat("default", null, "Prompt", null);
        String directFormatted = aiService.formatChatResponse(buildChatResponse(rawText, finishReason, usage),
                new TurnToolContext("default", WorkflowPhase.CODE, new AtomicInteger(0)));

        assertEquals(directFormatted, result);
        for (String snippet : expectedSnippets) {
            assertTrue(result.toLowerCase().contains(snippet.toLowerCase()));
        }
        verify(requestSpec, times(1)).call();
        assertEquals(result, captureTurnCompleteEvent().result());
    }

    @Test
    void testBlankResponseAfterToolExecutionWithStopReturnsDiagnosticMessage() {
        stubToolTurnsAndResponse(3, "", "STOP", new DefaultUsage(2500, 300, 2800));
        String result = aiService.chat("default", null, "Analyze project", null);
        SwingTestUtils.assertContainsAll(result, "STOP", "3",
                "promptTokens=2500, completionTokens=300, totalTokens=2800");
        assertTrue(result.toLowerCase().contains("continue"));
        verify(requestSpec, times(1)).call();
    }

    @Test
    void testChatPropagatesCancellationAndPostCallInterruption() {
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);
        when(responseSpec.chatResponse()).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return buildChatResponse("Should be discarded due to interrupt", "STOP", null);
        });

        try {
            assertThrows(CancellationException.class, () -> aiService.chat("default", null, "Run code", null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testChatExceptionRethrowsWithoutPublishingTurnCompleteEvent() {
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);
        when(requestSpec.call()).thenThrow(new IllegalStateException("API failure"));

        assertThrows(IllegalStateException.class, () -> aiService.chat("default", null, "Trigger error", null));
        verify(eventPublisher, never()).publishEvent(any(AgentTurnCompleteEvent.class));
    }

    @Test
    void testGenerateTopicTitleSanitizesAndFallsBackOnBlankOrError() {
        assertEquals(AiService.DEFAULT_TOPIC_TITLE, aiService.generateTopicTitle("   "));

        when(responseSpec.chatResponse())
                .thenReturn(buildChatResponse("  '# **Memory Service RipGrep Search**'\nExtra line", "STOP", null));
        assertEquals("Memory Service RipGrep Search",
                aiService.generateTopicTitle("User: How does memory search work?\nRoxy: It uses RipGrep."));
        verify(requestSpec).system(AiService.TOPIC_TITLE_SYSTEM_PROMPT);
        verify(requestSpec, never()).tools(any(Object[].class));

        when(responseSpec.chatResponse()).thenReturn(buildChatResponse("   ", "STOP", null));
        assertEquals("How does memory search work",
                aiService.generateTopicTitle("User: How does memory search work?\nRoxy: It uses RipGrep."));

        when(requestSpec.call()).thenThrow(new RuntimeException("LLM offline"));
        assertEquals("Exploring Swing HTML Constraints",
                aiService.generateTopicTitle("User: # Exploring Swing HTML Constraints!"));
    }

    @Test
    void testAiConfigCreatesGoogleGenAiClientAndRetryTemplate() throws Exception {
        AiConfig config = new AiConfig();
        GoogleGenAiConnectionProperties props = new GoogleGenAiConnectionProperties();
        props.setApiKey("test-api-key");
        try (Client client = config.googleGenAiClient(props, AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            assertEquals("test-api-key", client.apiKey());
            assertFalse(client.vertexAI());
        }
        assertNotNull(config.retryTemplate());
        assertTrue(AiConfig.isTransientAiError(new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED")));
        assertTrue(AiConfig.isTransientAiError(new ServerException(503, "Service Unavailable", "UNAVAILABLE")));
        assertTrue(AiConfig.isTransientAiError(
                new RuntimeException("Failed to generate content", new SocketTimeoutException("Read timed out"))));
        assertTrue(AiConfig.isTransientAiError(new GenAiIOException("Network failure")));
        assertTrue(AiConfig.isTransientAiError(new TransientAiException("Rate limit")));

        assertFalse(AiConfig.isTransientAiError(new ClientException(400, "Bad Request", "INVALID_ARGUMENT")));
        assertFalse(AiConfig.isTransientAiError(new ClientException(401, "Unauthorized", "UNAUTHENTICATED")));
        assertFalse(AiConfig.isTransientAiError(new ClientException(403, "Forbidden", "PERMISSION_DENIED")));
        assertFalse(AiConfig.isTransientAiError(new NonTransientAiException("Invalid API key")));
        assertFalse(AiConfig.isTransientAiError(new CancellationException("Stopped")));
    }

    @Test
    void testChatRetriesOnTransientErrorsAndSucceeds() {
        aiService.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0));
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Recovered Response", "STOP", null);

        AtomicInteger attempts = new AtomicInteger();
        when(requestSpec.call()).thenAnswer(inv -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("Failed to generate content",
                        new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED"));
            }
            if (attempt == 2) {
                throw new RuntimeException("Failed to generate content", new SocketTimeoutException("Read timed out"));
            }
            return responseSpec;
        });

        assertEquals("Recovered Response", aiService.chat("default", null, "Retry test", null));
        assertEquals(3, attempts.get());
        verify(requestSpec, times(3)).call();
        assertEquals("Recovered Response", captureTurnCompleteEvent().result());
    }

    @Test
    void testChatExhaustsMaxRetryAttemptsOnPersistentTransientError() {
        aiService.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0));
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);
        when(requestSpec.call()).thenThrow(new ServerException(503, "Service Unavailable", "UNAVAILABLE"));

        assertThrows(ServerException.class, () -> aiService.chat("default", null, "Fail all retries", null));
        verify(requestSpec, times(3)).call();
        verify(eventPublisher, never()).publishEvent(any(AgentTurnCompleteEvent.class));
    }

    @Test
    void testChatDoesNotRetryOnCancellationInterruptionOrNonRetryableClientErrors() {
        aiService.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0));
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);

        for (RuntimeException nonRetryable : List.of(new ClientException(400, "Bad Request", "INVALID_ARGUMENT"),
                new ClientException(401, "Unauthorized", "UNAUTHENTICATED"),
                new ClientException(403, "Forbidden", "PERMISSION_DENIED"), new NonTransientAiException("Bad request"),
                new CancellationException("Cancelled by user"))) {
            clearInvocations(requestSpec);
            doThrow(nonRetryable).when(requestSpec).call();
            try {
                assertThrows(RuntimeException.class, () -> aiService.chat("default", null, "Non-retryable", null));
                verify(requestSpec, times(1)).call();
            } finally {
                Thread.interrupted();
            }
        }

        clearInvocations(requestSpec);
        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to generate content", new SocketTimeoutException("Interrupted I/O"));
        }).when(requestSpec).call();
        try {
            assertThrows(CancellationException.class, () -> aiService.chat("default", null, "Interrupt test", null));
            verify(requestSpec, times(1)).call();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testModelCallRetryAdvisorRetriesSecondTurnWithoutReExecutingCompletedToolTurn() {
        ChatModel mockChatModel = mock(ChatModel.class);
        when(mockChatModel.getOptions()).thenReturn(
                org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build());
        ChatMemory realMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(10).build();
        ToolCallingManager customToolManager = mock(ToolCallingManager.class);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "executeJexl", "{}");
        AssistantMessage toolCallMsg = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        ChatResponse firstTurnResponse = new ChatResponse(
                List.of(new Generation(toolCallMsg, ChatGenerationMetadata.NULL)));
        ChatResponse secondTurnResponse = buildChatResponse("Final answer after tool & retry", "STOP", null);

        AtomicInteger modelCalls = new AtomicInteger();
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int callNum = modelCalls.incrementAndGet();
            if (callNum == 1) {
                return firstTurnResponse;
            }
            if (callNum == 2) {
                throw new ServerException(503, "Service Unavailable", "UNAVAILABLE");
            }
            return secondTurnResponse;
        });

        AtomicInteger toolExecutions = new AtomicInteger();
        when(customToolManager.executeToolCalls(any(Prompt.class), eq(firstTurnResponse))).thenAnswer(inv -> {
            toolExecutions.incrementAndGet();
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("Run tool"), toolCallMsg)).returnDirect(false).build();
        });

        ChatClient.Builder realBuilder = ChatClient.builder(mockChatModel);
        AiService serviceWithRealClient = new AiService(realBuilder, settingsService, promptService, jexlTool,
                planService, realMemory, customToolManager, eventPublisher);
        serviceWithRealClient.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0));

        when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE, null)).thenReturn("EXPLORE PROMPT");

        String result = serviceWithRealClient.chat("qa:default", WorkflowPhase.EXPLORE, "Run tool", null);
        assertEquals("Final answer after tool & retry", result);
        assertEquals(1, toolExecutions.get(),
                "Completed tool turn must not be re-executed when subsequent model call retries");
        assertEquals(3, modelCalls.get(), "Second model call should be retried once after transient 503");
    }

    @Test
    void testAutoNudgeAdvisorExecutesMultipleNudgesWithoutChainCrashAndSendsNudgePrompt() {
        ChatModel mockChatModel = mock(ChatModel.class);
        when(mockChatModel.getOptions()).thenReturn(
                org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build());
        ChatMemory realMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        ToolCallingManager customToolManager = mock(ToolCallingManager.class);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "executeJexl", "{}");
        AssistantMessage toolCallMsg = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        ChatResponse firstTurnToolCall = new ChatResponse(
                List.of(new Generation(toolCallMsg, ChatGenerationMetadata.NULL)));
        ChatResponse emptyStopResponse = buildChatResponse("", "STOP", null);
        ChatResponse finalNudgedResponse = buildChatResponse("Summary after second nudge", "STOP", null);

        List<Prompt> capturedPrompts = new java.util.ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            capturedPrompts.add(prompt);
            int callNum = modelCalls.incrementAndGet();
            if (callNum == 1) {
                return firstTurnToolCall;
            }
            if (callNum == 2 || callNum == 3) {
                return emptyStopResponse;
            }
            return finalNudgedResponse;
        });

        when(customToolManager.executeToolCalls(any(Prompt.class), eq(firstTurnToolCall))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            Map<String, Object> ctx = prompt
                    .getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOpts
                            ? toolOpts.getToolContext()
                            : Map.of();
            Object counter = ctx.get(TurnToolContext.TURN_COUNTER_KEY);
            if (counter instanceof AtomicInteger atomicCounter) {
                atomicCounter.incrementAndGet();
            }
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("Run tool"), toolCallMsg)).returnDirect(false).build();
        });

        ChatClient.Builder realBuilder = ChatClient.builder(mockChatModel);
        AiService serviceWithRealClient = new AiService(realBuilder, settingsService, promptService, jexlTool,
                planService, realMemory, customToolManager, eventPublisher);
        when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE, null)).thenReturn("EXPLORE SYSTEM PROMPT");

        String result = serviceWithRealClient.chat("qa:nudge-ok", WorkflowPhase.EXPLORE, "Run tool", null);
        assertEquals("Summary after second nudge", result);
        assertEquals(4, modelCalls.get(),
                "Expected 1 tool-call turn + 1 empty post-tool turn + 2 auto-nudge turns = 4 model calls");

        Prompt firstNudgePrompt = capturedPrompts.get(2);
        Prompt secondNudgePrompt = capturedPrompts.get(3);
        assertTrue(
                firstNudgePrompt.getInstructions().stream()
                        .anyMatch(m -> m instanceof UserMessage && AiService.AUTO_NUDGE_PROMPT.equals(m.getText())),
                "First nudge Prompt must contain AUTO_NUDGE_PROMPT");
        assertTrue(
                secondNudgePrompt.getInstructions().stream()
                        .anyMatch(m -> m instanceof UserMessage && AiService.AUTO_NUDGE_PROMPT.equals(m.getText())),
                "Second nudge Prompt must contain AUTO_NUDGE_PROMPT");

        // Also verify exhausting MAX_AUTO_NUDGES (all empty responses) does not throw IllegalArgumentException
        capturedPrompts.clear();
        modelCalls.set(0);
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            capturedPrompts.add(inv.getArgument(0));
            int callNum = modelCalls.incrementAndGet();
            if (callNum == 1) {
                return firstTurnToolCall;
            }
            return emptyStopResponse;
        });

        String exhaustedResult = serviceWithRealClient.chat("qa:nudge-exhausted", WorkflowPhase.EXPLORE, "Run tool",
                null);
        assertTrue(exhaustedResult.contains(AiService.EMPTY_RESPONSE_MARKER));
        assertEquals(2 + AiService.MAX_AUTO_NUDGES, modelCalls.get(),
                "Should stop nudging after MAX_AUTO_NUDGES attempts without throwing IllegalArgumentException");
    }

    @Test
    void testGenerateTopicTitleTimesOutQuicklyAndFallsBack() throws Exception {
        aiService.setTopicTitleTimeout(Duration.ofMillis(50));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        when(responseSpec.chatResponse()).thenAnswer(inv -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return buildChatResponse("Delayed Title", "STOP", null);
        });
        long startNanos = System.nanoTime();
        String title = aiService.generateTopicTitle("User: Why did Gemini time out?");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals("Why did Gemini time out", title);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS),
                "Stalled title generation call should be interrupted on timeout");
        assertTrue(wasInterrupted.get());
        assertTrue(elapsedMs < 2000, "Topic title timeout fallback should complete quickly, took " + elapsedMs + "ms");
    }
}
