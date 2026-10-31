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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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
        ChatGenerationMetadata genMeta = ChatGenerationMetadata.NULL;
        if (finishReason != null) {
            genMeta = ChatGenerationMetadata.builder().finishReason(finishReason).build();
        }
        ChatResponseMetadata responseMeta = new ChatResponseMetadata();
        if (usage != null) {
            responseMeta = ChatResponseMetadata.builder().usage(usage).build();
        }
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

    private static AssistantMessage createToolCallMessage() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "executeJexl", "{}");
        return AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
    }

    private static ChatResponse createToolCallResponse(AssistantMessage toolCallMsg) {
        return new ChatResponse(List.of(new Generation(toolCallMsg, ChatGenerationMetadata.NULL)));
    }

    private AiService createRealClientService(ChatModel mockChatModel, ToolCallingManager customToolManager) {
        when(mockChatModel.getOptions()).thenReturn(GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build());
        ChatMemory realMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        AiService service = new AiService(ChatClient.builder(mockChatModel), settingsService, promptService, jexlTool,
                planService, realMemory, customToolManager, eventPublisher);
        service.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0));
        when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE, null)).thenReturn("EXPLORE PROMPT");
        return service;
    }

    @Test
    void testAiConfigCreatesBeansClientAndRetryClassifier() throws Exception {
        AiConfig config = new AiConfig();
        ToolExecutionExceptionProcessor processor = config.toolExecutionExceptionProcessor();
        assertNotNull(processor);
        ToolDefinition toolDef = mock(ToolDefinition.class);
        String jsonError = processor
                .process(new ToolExecutionException(toolDef, new IllegalArgumentException("Invalid parameter")));
        assertEquals(Map.of("error", "Invalid parameter"),
                AiConfig.SHARED_OBJECT_MAPPER.readValue(jsonError, Map.class));
        String fallbackJsonError = processor
                .process(new ToolExecutionException(toolDef, new IllegalArgumentException("   ")));
        assertEquals(Map.of("error", "Tool execution failed"),
                AiConfig.SHARED_OBJECT_MAPPER.readValue(fallbackJsonError, Map.class));
        assertThrows(CancellationException.class, () -> processor
                .process(new ToolExecutionException(toolDef, new CancellationException("User stopped"))));
        assertNotNull(config.toolCallingManager(processor));

        GoogleGenAiConnectionProperties props = new GoogleGenAiConnectionProperties();
        props.setApiKey("test-api-key");
        try (Client client = config.googleGenAiClient(props, AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            assertEquals("test-api-key", client.apiKey());
            assertFalse(client.vertexAI());
        }
        Thread.interrupted();
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
    @SuppressWarnings("unchecked")
    void testChatUsesConfiguredModelDynamicPromptAndTurnEvents() {
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

        clearInvocations(requestSpec, eventPublisher);
        when(promptService.buildSystemPrompt(WorkflowPhase.REVIEW, "Extra instructions"))
                .thenReturn("REVIEW SYSTEM PROMPT");
        assertEquals("AI Response",
                aiService.chat("custom-conv", WorkflowPhase.REVIEW, "Hello", "Extra instructions", null));
        verify(requestSpec).system("REVIEW SYSTEM PROMPT");

        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).advisors(advisorCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        advisorCaptor.getValue().accept(advisorSpec);
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "custom-conv");

        ProjectService mockProjectService = mock(ProjectService.class);
        when(mockProjectService.getEffectiveGeminiModel()).thenReturn("gemini-project-override");
        AiService aiServiceWithProject = new AiService(chatClientBuilder, settingsService, mockProjectService,
                promptService, jexlTool, planService, chatMemory, toolCallingManager, eventPublisher);
        assertEquals("gemini-project-override", aiServiceWithProject.resolveEffectiveGeminiModel());
        assertEquals("AI Response", aiServiceWithProject.chat("default", WorkflowPhase.CODE, "Hello", null));

        clearInvocations(eventPublisher);
        String msg = "Autonomous execution stopped: Maximum tool turns (2) exceeded.";
        stubToolTurnsAndResponse(2, msg, "STOP", null);
        assertEquals(msg, aiService.chat("default", null, "Hello", null));
        AgentTurnCompleteEvent ev = captureTurnCompleteEvent();
        assertEquals(2, ev.totalTurns());
        assertEquals(msg, ev.result());

        clearInvocations(eventPublisher);
        when(requestSpec.call()).thenThrow(new IllegalStateException("API failure"));
        assertThrows(IllegalStateException.class, () -> aiService.chat("default", null, "Trigger error", null));
        verify(eventPublisher, never()).publishEvent(any(AgentTurnCompleteEvent.class));
    }

    private static Arguments caseArgs(int turns, String text, String reason, Usage usage, String... snippets) {
        return Arguments.of(turns, text, reason, usage, List.of(snippets));
    }

    static Stream<Arguments> finishReasonFormattingCases() {
        return Stream.of(caseArgs(0, "Complete answer.", "STOP", new DefaultUsage(100, 50, 150), "Complete answer."),
                caseArgs(0, "Partial output here...", "MAX_TOKENS", new DefaultUsage(1200, 8192, 9392),
                        "Partial output here...", "MAX_TOKENS",
                        "promptTokens=1200, completionTokens=8192, totalTokens=9392", "continue"),
                caseArgs(0, "   ", "MAX_TOKENS", new DefaultUsage(4000, 8192, 12192), "MAX_TOKENS",
                        "promptTokens=4000, completionTokens=8192, totalTokens=12192", "continue"),
                caseArgs(0, null, "MALFORMED_FUNCTION_CALL", null, "MALFORMED_FUNCTION_CALL", "blocked or aborted"),
                caseArgs(0, "", "SAFETY", null, "SAFETY", "blocked or aborted"),
                caseArgs(3, "", "STOP", new DefaultUsage(2500, 300, 2800), "STOP", "3",
                        "promptTokens=2500, completionTokens=300, totalTokens=2800", "continue"));
    }

    @ParameterizedTest
    @MethodSource("finishReasonFormattingCases")
    void testChatResponseFormattingByFinishReason(int turns, String rawText, String finishReason, Usage usage,
            List<String> expectedSnippets) {
        if (turns > 0) {
            stubToolTurnsAndResponse(turns, rawText, finishReason, usage);
        } else {
            stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", rawText, finishReason, usage);
        }

        String result = aiService.chat("default", null, "Prompt", null);
        String directFormatted = aiService.formatChatResponse(buildChatResponse(rawText, finishReason, usage),
                new TurnToolContext("default", WorkflowPhase.CODE, new AtomicInteger(turns)));

        assertEquals(directFormatted, result);
        for (String snippet : expectedSnippets) {
            assertTrue(result.toLowerCase().contains(snippet.toLowerCase()));
        }
        verify(requestSpec, times(1)).call();
        assertEquals(result, captureTurnCompleteEvent().result());
    }

    @Test
    void testChatRetryPolicyOnTransientNonRetryableAndInterruptedErrors() {
        ChatModel mockChatModel = mock(ChatModel.class);
        AiService realRetryService = createRealClientService(mockChatModel, toolCallingManager);
        ChatResponse recoveredResponse = buildChatResponse("Recovered Response", "STOP", null);

        AtomicInteger attempts = new AtomicInteger();
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("Failed to generate content",
                        new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED"));
            }
            if (attempt == 2) {
                throw new RuntimeException("Failed to generate content", new SocketTimeoutException("Read timed out"));
            }
            return recoveredResponse;
        });

        assertEquals("Recovered Response", realRetryService.chat("default", WorkflowPhase.EXPLORE, "Retry test", null));
        assertEquals(3, attempts.get());
        assertEquals("Recovered Response", captureTurnCompleteEvent().result());

        clearInvocations(mockChatModel, eventPublisher);
        when(mockChatModel.call(any(Prompt.class)))
                .thenThrow(new ServerException(503, "Service Unavailable", "UNAVAILABLE"));
        assertThrows(ServerException.class,
                () -> realRetryService.chat("default", WorkflowPhase.EXPLORE, "Fail all retries", null));
        verify(mockChatModel, times(3)).call(any(Prompt.class));
        verify(eventPublisher, never()).publishEvent(any(AgentTurnCompleteEvent.class));

        for (RuntimeException nonRetryable : List.of(new ClientException(400, "Bad Request", "INVALID_ARGUMENT"),
                new ClientException(401, "Unauthorized", "UNAUTHENTICATED"),
                new ClientException(403, "Forbidden", "PERMISSION_DENIED"), new NonTransientAiException("Bad request"),
                new CancellationException("Cancelled by user"))) {
            clearInvocations(mockChatModel);
            doThrow(nonRetryable).when(mockChatModel).call(any(Prompt.class));
            try {
                assertThrows(RuntimeException.class,
                        () -> realRetryService.chat("default", WorkflowPhase.EXPLORE, "Non-retryable", null));
                verify(mockChatModel, times(1)).call(any(Prompt.class));
            } finally {
                Thread.interrupted();
            }
        }

        clearInvocations(mockChatModel);
        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to generate content", new SocketTimeoutException("Interrupted I/O"));
        }).when(mockChatModel).call(any(Prompt.class));
        try {
            assertThrows(CancellationException.class,
                    () -> realRetryService.chat("default", WorkflowPhase.EXPLORE, "Interrupt test", null));
            verify(mockChatModel, times(1)).call(any(Prompt.class));
        } finally {
            Thread.interrupted();
        }

        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return buildChatResponse("Should be discarded due to interrupt", "STOP", null);
        });
        try {
            assertThrows(CancellationException.class,
                    () -> realRetryService.chat("default", WorkflowPhase.EXPLORE, "Run code", null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testModelCallRetryAdvisorRetriesSecondTurnWithoutReExecutingCompletedToolTurn() {
        ChatModel mockChatModel = mock(ChatModel.class);
        ToolCallingManager customToolManager = mock(ToolCallingManager.class);
        AiService serviceWithRealClient = createRealClientService(mockChatModel, customToolManager);

        AssistantMessage toolCallMsg = createToolCallMessage();
        ChatResponse firstTurnResponse = createToolCallResponse(toolCallMsg);
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

        String result = serviceWithRealClient.chat("qa:default", WorkflowPhase.EXPLORE, "Run tool", null);
        assertEquals("Final answer after tool & retry", result);
        assertEquals(1, toolExecutions.get(),
                "Completed tool turn must not be re-executed when subsequent model call retries");
        assertEquals(3, modelCalls.get(), "Second model call should be retried once after transient 503");
    }

    @Test
    void testAutoNudgeAdvisorExecutesMultipleNudgesWithoutChainCrashAndSendsNudgePrompt() {
        ChatModel mockChatModel = mock(ChatModel.class);
        ToolCallingManager customToolManager = mock(ToolCallingManager.class);
        AiService serviceWithRealClient = createRealClientService(mockChatModel, customToolManager);

        AssistantMessage toolCallMsg = createToolCallMessage();
        ChatResponse firstTurnToolCall = createToolCallResponse(toolCallMsg);
        ChatResponse emptyStopResponse = buildChatResponse("", "STOP", null);
        ChatResponse finalNudgedResponse = buildChatResponse("Summary after second nudge", "STOP", null);

        List<Prompt> capturedPrompts = new ArrayList<>();
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
            Map<String, Object> ctx = Map.of();
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolOpts) {
                ctx = toolOpts.getToolContext();
            }
            if (ctx.get(TurnToolContext.TURN_COUNTER_KEY) instanceof AtomicInteger atomicCounter) {
                atomicCounter.incrementAndGet();
            }
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("Run tool"), toolCallMsg)).returnDirect(false).build();
        });

        String result = serviceWithRealClient.chat("qa:nudge-ok", WorkflowPhase.EXPLORE, "Run tool", null);
        assertEquals("Summary after second nudge", result);
        assertEquals(4, modelCalls.get());
        for (int idx : List.of(2, 3)) {
            assertTrue(capturedPrompts.get(idx).getInstructions().stream()
                    .anyMatch(m -> m instanceof UserMessage && AiService.AUTO_NUDGE_PROMPT.equals(m.getText())));
        }

        capturedPrompts.clear();
        modelCalls.set(0);
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            capturedPrompts.add(inv.getArgument(0));
            if (modelCalls.incrementAndGet() == 1) {
                return firstTurnToolCall;
            }
            return emptyStopResponse;
        });

        String exhaustedResult = serviceWithRealClient.chat("qa:nudge-exhausted", WorkflowPhase.EXPLORE, "Run tool",
                null);
        assertTrue(exhaustedResult.contains(AiService.EMPTY_RESPONSE_MARKER));
        assertEquals(2 + AiService.MAX_AUTO_NUDGES, modelCalls.get());
    }

    @Test
    void testGenerateTopicTitleSanitizesAndFallsBackOnErrorOrTimeout() throws Exception {
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

        doReturn(responseSpec).when(requestSpec).call();
        aiService.setTopicTitleTimeout(Duration.ofMillis(50));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseSlowCall = new CountDownLatch(1);

        when(responseSpec.chatResponse()).thenAnswer(inv -> {
            started.countDown();
            releaseSlowCall.await(2, TimeUnit.SECONDS);
            return buildChatResponse("Delayed Title", "STOP", null);
        });
        long startNanos = System.nanoTime();
        String title;
        try {
            title = aiService.generateTopicTitle("User: Why did Gemini time out?");
        } finally {
            releaseSlowCall.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals("Why did Gemini time out", title);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(elapsedMs < 2000);
    }

    @Test
    void testChatTurnTimeoutInterruptsHungTurnAndThrowsCancellationException() throws Exception {
        assertEquals(AiConfig.DEFAULT_TURN_TIMEOUT_SECONDS, aiService.getTurnTimeoutSeconds());
        aiService.setTurnTimeoutSeconds(1);
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "Ignored", "STOP", null);
        CountDownLatch interrupted = new CountDownLatch(1);

        when(responseSpec.chatResponse()).thenAnswer(inv -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new RuntimeException("Model call interrupted by watchdog", e);
            }
            return buildChatResponse("Should not reach", "STOP", null);
        });

        try {
            assertThrows(CancellationException.class, () -> aiService.chat("default", null, "Hung request", null));
            assertTrue(Thread.interrupted());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Turn watchdog should interrupt hung turn thread");
        } finally {
            Thread.interrupted();
        }
    }
}
