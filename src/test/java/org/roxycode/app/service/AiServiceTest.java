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
import org.roxycode.app.events.SettingsChangedEvent;
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
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        stubMockClientChain(chatClientBuilder, chatClient, requestSpec, responseSpec);

        jexlTool = new JexlTool(jexlServiceRegistry, eventPublisher, null, settingsService);
        aiService = new AiService(chatClientBuilder, settingsService, promptService, jexlTool, planService, chatMemory,
                toolCallingManager, eventPublisher);
    }

    @SuppressWarnings("unchecked")
    private static void stubMockClientChain(ChatClient.Builder builder, ChatClient client,
            ChatClient.ChatClientRequestSpec reqSpec, ChatClient.CallResponseSpec respSpec) {
        lenient().when(client.prompt()).thenReturn(reqSpec);
        lenient().when(reqSpec.system(anyString())).thenReturn(reqSpec);
        lenient().when(reqSpec.user(any(Consumer.class))).thenReturn(reqSpec);
        lenient().when(reqSpec.advisors(any(Consumer.class))).thenReturn(reqSpec);
        lenient().when(reqSpec.advisors(any(Advisor[].class))).thenReturn(reqSpec);
        lenient().when(reqSpec.options(any())).thenReturn(reqSpec);
        lenient().when(reqSpec.toolContext(anyMap())).thenReturn(reqSpec);
        lenient().when(reqSpec.tools(any(Object[].class))).thenReturn(reqSpec);
        lenient().when(reqSpec.call()).thenReturn(respSpec);
        lenient().when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(client);
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
        ToolDefinition toolDef = mock(ToolDefinition.class);
        assertEquals(Map.of("error", "Invalid parameter"),
                AiConfig.SHARED_OBJECT_MAPPER.readValue(
                        processor.process(
                                new ToolExecutionException(toolDef, new IllegalArgumentException("Invalid parameter"))),
                        Map.class));
        assertThrows(CancellationException.class, () -> processor
                .process(new ToolExecutionException(toolDef, new CancellationException("User stopped"))));

        GoogleGenAiConnectionProperties props = new GoogleGenAiConnectionProperties();
        props.setApiKey("test-api-key");
        try (Client client = config.googleGenAiClient(props, AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            assertEquals("test-api-key", client.apiKey());
            assertFalse(client.vertexAI());
        }
        Thread.interrupted();
        assertTrue(AiConfig.isTransientAiError(new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED")));
        assertTrue(AiConfig.isTransientAiError(new ServerException(503, "Service Unavailable", "UNAVAILABLE")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("Err", new SocketTimeoutException("Timeout"))));
        assertTrue(AiConfig.isTransientAiError(new GenAiIOException("Network failure")));
        assertTrue(AiConfig.isTransientAiError(new TransientAiException("Rate limit")));
        assertFalse(AiConfig.isTransientAiError(new ClientException(400, "Bad Request", "INVALID_ARGUMENT")));
        assertFalse(AiConfig.isTransientAiError(new ClientException(401, "Unauthorized", "UNAUTHENTICATED")));
        assertFalse(AiConfig.isTransientAiError(new NonTransientAiException("Invalid API key")));
        assertFalse(AiConfig.isTransientAiError(new CancellationException("Stopped")));
    }

    @Test
    void testChatUsesConfiguredModelDynamicPromptHotReloadAndTurnEvents() {
        settings.setGeminiModel("test-model-123");
        stubChatResponse(WorkflowPhase.CODE, "FULL CODE SYSTEM PROMPT", "AI Response", "STOP", null);

        assertEquals("AI Response", aiService.chat("default", null, "Hello", null));
        verify(requestSpec).system("FULL CODE SYSTEM PROMPT");
        verify(requestSpec).tools(jexlTool);

        ProjectService mockProjectService = mock(ProjectService.class);
        when(mockProjectService.getEffectiveGeminiModel()).thenReturn("gemini-project-override");
        AiService aiServiceWithProject = new AiService(chatClientBuilder, settingsService, mockProjectService,
                promptService, jexlTool, planService, chatMemory, toolCallingManager, eventPublisher);
        assertEquals("gemini-project-override", aiServiceWithProject.resolveEffectiveGeminiModel());

        clearInvocations(eventPublisher);
        String msg = "Autonomous execution stopped: Maximum tool turns (2) exceeded.";
        stubToolTurnsAndResponse(2, msg, "STOP", null);
        assertEquals(msg, aiService.chat("default", null, "Hello", null));
        assertEquals(2, captureTurnCompleteEvent().totalTurns());

        AtomicReference<String> reloadedKeyRef = new AtomicReference<>();
        aiService.setChatClientBuilderFactory(key -> {
            reloadedKeyRef.set(key);
            return chatClientBuilder;
        });
        settings.setGeminiApiKey("  hot-reloaded-key-123  ");
        aiService.onSettingsChanged(new SettingsChangedEvent(settings));
        assertEquals("hot-reloaded-key-123", reloadedKeyRef.get());
        assertEquals("hot-reloaded-key-123", aiService.getActiveApiKey());

        assertThrows(IllegalArgumentException.class, () -> aiService.testConnection("   ", "gemini-3.5-flash"));
        aiService.setConnectionTester((key, model) -> key + ":" + model);
        assertEquals("my-key:gemini-3.5-pro", aiService.testConnection("  my-key  ", " gemini-3.5-pro "));
    }

    static Stream<Arguments> finishReasonFormattingCases() {
        return Stream.of(
                Arguments.of(0, "Complete answer.", "STOP", new DefaultUsage(100, 50, 150),
                        List.of("Complete answer.")),
                Arguments.of(0, "Partial output...", "MAX_TOKENS", new DefaultUsage(1200, 8192, 9392),
                        List.of("Partial output...", "MAX_TOKENS", "totalTokens=9392")),
                Arguments.of(0, "   ", "MAX_TOKENS", new DefaultUsage(4000, 8192, 12192),
                        List.of("MAX_TOKENS", "totalTokens=12192")),
                Arguments.of(0, null, "MALFORMED_FUNCTION_CALL", null,
                        List.of("MALFORMED_FUNCTION_CALL", "blocked or aborted")),
                Arguments.of(3, "", "STOP", new DefaultUsage(2500, 300, 2800),
                        List.of("STOP", "3", "totalTokens=2800")));
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
        for (String snippet : expectedSnippets) {
            assertTrue(result.toLowerCase().contains(snippet.toLowerCase()));
        }
        assertEquals(result, captureTurnCompleteEvent().result());
    }

    @Test
    void testChatRetryPolicyAndSecondTurnRetryWithoutReExecutingTool() {
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

        assertEquals("Final answer after tool & retry",
                serviceWithRealClient.chat("qa:default", WorkflowPhase.EXPLORE, "Run tool", null));
        assertEquals(1, toolExecutions.get());
        assertEquals(3, modelCalls.get());

        clearInvocations(mockChatModel);
        doThrow(new ClientException(400, "Bad Request", "INVALID_ARGUMENT")).when(mockChatModel)
                .call(any(Prompt.class));
        assertThrows(RuntimeException.class,
                () -> serviceWithRealClient.chat("qa:default", WorkflowPhase.EXPLORE, "Non-retryable", null));
        verify(mockChatModel, times(1)).call(any(Prompt.class));

        clearInvocations(mockChatModel);
        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", new SocketTimeoutException("Interrupted I/O"));
        }).when(mockChatModel).call(any(Prompt.class));
        try {
            assertThrows(CancellationException.class,
                    () -> serviceWithRealClient.chat("qa:default", WorkflowPhase.EXPLORE, "Interrupt", null));
            verify(mockChatModel, times(1)).call(any(Prompt.class));
        } finally {
            Thread.interrupted();
        }
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
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolOpts
                    && toolOpts.getToolContext().get(TurnToolContext.TURN_COUNTER_KEY) instanceof AtomicInteger c) {
                c.incrementAndGet();
            }
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("Run tool"), toolCallMsg)).returnDirect(false).build();
        });

        assertEquals("Summary after second nudge",
                serviceWithRealClient.chat("qa:nudge-ok", WorkflowPhase.EXPLORE, "Run tool", null));
        assertEquals(4, modelCalls.get());
        assertTrue(capturedPrompts.get(2).getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage && AiService.AUTO_NUDGE_PROMPT.equals(m.getText())));
    }
}
