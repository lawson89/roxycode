package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
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
import org.roxycode.app.ai.AiMemoryConfig;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.ThinkingLevel;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
        aiService = new AiService(chatClientBuilder, settingsService, null, promptService, jexlTool, planService,
                chatMemory, toolCallingManager, eventPublisher);
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
        lenient().when(promptService.buildSystemPrompt(phase)).thenReturn(promptText);
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
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        for (Object event : captor.getAllValues()) {
            if (event instanceof AgentTurnCompleteEvent turnComplete) {
                return turnComplete;
            }
        }
        fail("Expected AgentTurnCompleteEvent to be published");
        return null;
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
        AiService service = new AiService(ChatClient.builder(mockChatModel), settingsService, null, promptService,
                jexlTool, planService, realMemory, customToolManager, eventPublisher);
        service.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0), 3);
        lenient().when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE)).thenReturn("EXPLORE PROMPT");
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

        GoogleGenAiConnectionProperties unconfiguredProps = new GoogleGenAiConnectionProperties();
        try (Client placeholderClient = config.googleGenAiClient(unconfiguredProps, AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            assertEquals(AiConfig.UNCONFIGURED_API_KEY_PLACEHOLDER, placeholderClient.apiKey());
            assertFalse(placeholderClient.vertexAI());
        }

        GoogleGenAiConnectionProperties incompleteVertexProps = new GoogleGenAiConnectionProperties();
        incompleteVertexProps.setVertexAi(true);
        try (Client incompleteVertexClient = config.googleGenAiClient(incompleteVertexProps,
                AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            assertEquals(AiConfig.UNCONFIGURED_API_KEY_PLACEHOLDER, incompleteVertexClient.apiKey());
            assertFalse(incompleteVertexClient.vertexAI());
        }

        assertThrows(IllegalArgumentException.class,
                () -> AiConfig.createGenAiClient(AiConfig.GenAiConnectionConfig.forVertexAi("", "global", ""), 5000));
        assertThrows(IllegalArgumentException.class,
                () -> AiConfig.createGenAiClient(AiConfig.GenAiConnectionConfig.forVertexAi("proj-1", "", ""), 5000));
        IllegalArgumentException missingFileEx = assertThrows(IllegalArgumentException.class,
                () -> AiConfig.createGenAiClient(
                        AiConfig.GenAiConnectionConfig.forVertexAi("proj-1", "global", "/nonexistent/sa-key.json"),
                        5000));
        assertTrue(missingFileEx.getMessage().contains("Service Account JSON file not found"));
        Thread.interrupted();
        assertTrue(AiConfig.isTransientAiError(new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED")));
        assertTrue(AiConfig.isTransientAiError(new ClientException(499, "Client Closed Request", "CANCELLED")));
        assertTrue(AiConfig.isTransientAiError(new ServerException(503, "Service Unavailable", "UNAVAILABLE")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("Err", new SocketTimeoutException("Timeout"))));
        assertTrue(AiConfig.isTransientAiError(new GenAiIOException("Network failure")));
        assertTrue(AiConfig.isTransientAiError(new TransientAiException("Rate limit")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("status=INTERNAL, An internal error occurred")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("The model is overloaded. Try again later.")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("HTTP/2 GOAWAY received from peer")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("Unexpected end of stream on connection")));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("Outer wrapper mentioning 404 NOT FOUND in context",
                new ServerException(503, "Service Unavailable", "UNAVAILABLE"))));
        assertTrue(AiConfig.isTransientAiError(new RuntimeException("Outer wrapper mentioning INVALID_ARGUMENT in logs",
                new SocketTimeoutException("Read timed out"))));
        assertFalse(AiConfig.isTransientAiError(new ClientException(400, "Bad Request", "INVALID_ARGUMENT")));
        assertFalse(AiConfig.isTransientAiError(new ClientException(401, "Unauthorized", "UNAUTHENTICATED")));
        assertFalse(AiConfig.isTransientAiError(new RuntimeException("Outer transient marker 503",
                new ClientException(400, "Bad Request", "INVALID_ARGUMENT"))));
        assertFalse(AiConfig.isTransientAiError(new NonTransientAiException("Invalid API key")));
        assertFalse(AiConfig.isTransientAiError(new CancellationException("Stopped")));

        assertTrue(AiConfig.isRateLimitError(new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED")));
        assertTrue(AiConfig.isRateLimitError(new TransientAiException("429 Quota exceeded for quota metric")));
        assertTrue(AiConfig
                .isRateLimitError(new RuntimeException("Wrapper", new TransientAiException("Rate limit exceeded"))));
        assertFalse(AiConfig.isRateLimitError(new ServerException(503, "Service Unavailable", "UNAVAILABLE")));
        assertFalse(AiConfig.isRateLimitError(new SocketTimeoutException("Read timed out")));
        assertFalse(AiConfig.isRateLimitError(new ClientException(400, "Bad Request", "INVALID_ARGUMENT")));

        AtomicInteger noOpAttempts = new AtomicInteger();
        Exception noOpEx = assertThrows(Exception.class, () -> config.retryTemplate().execute(() -> {
            noOpAttempts.incrementAndGet();
            throw new ServerException(503, "Overloaded", "UNAVAILABLE");
        }));
        assertEquals(1, noOpAttempts.get());
        assertTrue(AiConfig.isTransientAiError(noOpEx));
    }

    @Test
    void testChatUsesConfiguredModelDynamicPromptHotReloadAndTurnEvents() {
        settings.setAgentProfile("NORMAL");
        stubChatResponse(WorkflowPhase.CODE, "FULL CODE SYSTEM PROMPT", "AI Response", "STOP", null);

        assertEquals("AI Response", aiService.chat("default", null, "Hello", null));
        verify(requestSpec).system("FULL CODE SYSTEM PROMPT");
        verify(requestSpec).tools(jexlTool);

        ArgumentCaptor<GoogleGenAiChatOptions.Builder> optionsCaptor = ArgumentCaptor
                .forClass(GoogleGenAiChatOptions.Builder.class);
        verify(requestSpec).options(optionsCaptor.capture());
        GoogleGenAiChatOptions builtDefaultOptions = optionsCaptor.getValue().build();
        assertEquals("gemini-4-hydrogen", builtDefaultOptions.getModel());
        assertNull(builtDefaultOptions.getThinkingLevel());

        clearInvocations(requestSpec);
        when(promptService.buildSystemPrompt(WorkflowPhase.PLAN)).thenReturn("FULL PLAN SYSTEM PROMPT");
        assertEquals("AI Response", aiService.chat("default", WorkflowPhase.PLAN, "Plan feature", null));
        ArgumentCaptor<GoogleGenAiChatOptions.Builder> planOptionsCaptor = ArgumentCaptor
                .forClass(GoogleGenAiChatOptions.Builder.class);
        verify(requestSpec).options(planOptionsCaptor.capture());
        assertEquals("gemini-4-argon", planOptionsCaptor.getValue().build().getModel());

        ProjectService mockProjectService = mock(ProjectService.class);
        lenient().when(mockProjectService.getEffectiveAgentProfile()).thenReturn(AgentProfile.MAX);
        when(mockProjectService.getEffectiveGeminiModel(WorkflowPhase.PLAN)).thenReturn("gemini-4-carbon");
        when(mockProjectService.getEffectiveGeminiModel(WorkflowPhase.CODE)).thenReturn("gemini-4-hydrogen");
        AiService aiServiceWithProject = new AiService(chatClientBuilder, settingsService, mockProjectService,
                promptService, jexlTool, planService, chatMemory, toolCallingManager, eventPublisher);
        assertEquals(AgentProfile.MAX, aiServiceWithProject.resolveEffectiveAgentProfile());
        assertEquals("gemini-4-carbon", aiServiceWithProject.resolveEffectiveGeminiModel(WorkflowPhase.PLAN));
        assertEquals("gemini-4-hydrogen", aiServiceWithProject.resolveEffectiveGeminiModel(WorkflowPhase.CODE));
        assertEquals(ThinkingLevel.ADAPTIVE, aiServiceWithProject.resolveEffectiveThinkingLevel());

        clearInvocations(requestSpec);
        assertEquals("AI Response",
                aiServiceWithProject.chat("default", WorkflowPhase.PLAN, "Hello with Max profile", null));
        ArgumentCaptor<GoogleGenAiChatOptions.Builder> projectOptionsCaptor = ArgumentCaptor
                .forClass(GoogleGenAiChatOptions.Builder.class);
        verify(requestSpec).options(projectOptionsCaptor.capture());
        GoogleGenAiChatOptions builtProjectOptions = projectOptionsCaptor.getValue().build();
        assertEquals("gemini-4-carbon", builtProjectOptions.getModel());
        assertNull(builtProjectOptions.getThinkingLevel());

        clearInvocations(eventPublisher);
        String msg = "Autonomous execution stopped: Maximum tool turns (2) exceeded.";
        stubToolTurnsAndResponse(2, msg, "STOP", null);
        assertEquals(msg, aiService.chat("default", null, "Hello", null));
        assertEquals(2, captureTurnCompleteEvent().totalTurns());

        AtomicReference<AiConfig.GenAiConnectionConfig> reloadedKeyRef = new AtomicReference<>();
        aiService.setConnectionChatClientBuilderFactory(cfg -> {
            reloadedKeyRef.set(cfg);
            return chatClientBuilder;
        });
        settings.setGeminiApiKey("  hot-reloaded-key-123  ");
        aiService.onSettingsChanged(new SettingsChangedEvent(settings));
        assertEquals("hot-reloaded-key-123", reloadedKeyRef.get().apiKey());
        assertEquals("hot-reloaded-key-123", aiService.getActiveConnectionConfig().apiKey());

        settings.setGeminiApiKey("   ");
        aiService.onSettingsChanged(new SettingsChangedEvent(settings));
        assertEquals("", aiService.getActiveConnectionConfig().apiKey());

        AiMemoryConfig memoryConfig = new AiMemoryConfig();
        ChatMemory dynamicMemory = memoryConfig.chatMemory(memoryConfig.chatMemoryRepository(), settingsService);
        settings.setMaxChatMemoryMessages(5);
        dynamicMemory.add("conv-1", List.of(new SystemMessage("sys"), new UserMessage("u1"), new AssistantMessage("a1"),
                new UserMessage("u2"), new AssistantMessage("a2")));
        assertEquals(5, dynamicMemory.get("conv-1").size());

        settings.setMaxChatMemoryMessages(3);
        List<Message> shrunkOnGet = dynamicMemory.get("conv-1");
        assertEquals(3, shrunkOnGet.size());
        assertTrue(shrunkOnGet.get(0) instanceof SystemMessage);
        assertEquals("u2", shrunkOnGet.get(1).getText());
        assertEquals("a2", shrunkOnGet.get(2).getText());

        dynamicMemory.add("conv-1", List.of(new UserMessage("u3")));
        List<Message> shrunkOnAdd = dynamicMemory.get("conv-1");
        assertEquals(2, shrunkOnAdd.size());
        assertTrue(shrunkOnAdd.get(0) instanceof SystemMessage);
        assertEquals("u3", shrunkOnAdd.get(1).getText());

        assertThrows(IllegalArgumentException.class,
                () -> aiService.testConnection(AiConfig.GenAiConnectionConfig.forApiKey("   "), "gemini-3.5-flash"));
        aiService.setConnectionConfigTester((cfg, model) -> cfg.apiKey() + ":" + model);
        assertEquals("my-key:gemini-3.5-pro",
                aiService.testConnection(AiConfig.GenAiConnectionConfig.forApiKey("  my-key  "), " gemini-3.5-pro "));

        AtomicReference<AiConfig.GenAiConnectionConfig> reloadedConfigRef = new AtomicReference<>();
        aiService.setConnectionChatClientBuilderFactory(cfg -> {
            reloadedConfigRef.set(cfg);
            return chatClientBuilder;
        });
        settings.setVertexAiEnabled(true);
        settings.setVertexProjectId("  vertex-proj-42 ");
        settings.setVertexLocation(" us-central1 ");
        settings.setVertexCredentialsPath(" /tmp/creds.json ");
        aiService.onSettingsChanged(new SettingsChangedEvent(settings));
        assertNotNull(reloadedConfigRef.get());
        assertTrue(reloadedConfigRef.get().vertexAi());
        assertEquals("vertex-proj-42", reloadedConfigRef.get().projectId());
        assertEquals("us-central1", reloadedConfigRef.get().location());
        assertEquals("/tmp/creds.json", reloadedConfigRef.get().credentialsPath());
        assertEquals(reloadedConfigRef.get(), aiService.getActiveConnectionConfig());

        assertThrows(IllegalArgumentException.class, () -> aiService
                .testConnection(AiConfig.GenAiConnectionConfig.forVertexAi("", "global", ""), "gemini-3.5-flash"));
        assertThrows(IllegalArgumentException.class, () -> aiService
                .testConnection(AiConfig.GenAiConnectionConfig.forVertexAi("proj", "", ""), "gemini-3.5-flash"));
        aiService.setConnectionConfigTester(
                (cfg, model) -> cfg.vertexAi() + ":" + cfg.projectId() + ":" + cfg.location() + ":" + model);
        assertEquals("true:vertex-proj-42:us-central1:gemini-3.5-flash", aiService.testConnection(
                AiConfig.GenAiConnectionConfig.forVertexAi("vertex-proj-42", "us-central1", ""), "gemini-3.5-flash"));
    }

    @Test
    void testMainChatDoesNotEnableGoogleSearchGroundingAndGroundedSearchExecutesStatelessToolFreeQuery() {
        settings.setGoogleGroundingEnabled(true);
        stubChatResponse(WorkflowPhase.CODE, "CODE PROMPT", "AI Response", "STOP", null);

        aiService.chat("default", null, "Hello", null);

        ArgumentCaptor<GoogleGenAiChatOptions.Builder> chatOptionsCaptor = ArgumentCaptor
                .forClass(GoogleGenAiChatOptions.Builder.class);
        verify(requestSpec).options(chatOptionsCaptor.capture());
        GoogleGenAiChatOptions chatOptions = chatOptionsCaptor.getValue().build();
        assertNotEquals(Boolean.TRUE, chatOptions.getGoogleSearchRetrieval());
        verify(requestSpec).tools(jexlTool);

        clearInvocations(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(responseSpec.chatResponse())
                .thenReturn(buildChatResponse("  Grounded answer with citations  ", "STOP", null));

        assertThrows(IllegalArgumentException.class, () -> aiService.groundedSearch("   "));
        String groundedResult = aiService.groundedSearch("  Spring AI 2.0 release notes  ");
        assertEquals("Grounded answer with citations", groundedResult);

        ArgumentCaptor<GoogleGenAiChatOptions.Builder> searchOptionsCaptor = ArgumentCaptor
                .forClass(GoogleGenAiChatOptions.Builder.class);
        verify(requestSpec).system(AiService.GROUNDED_SEARCH_SYSTEM_PROMPT);
        verify(requestSpec).user("Spring AI 2.0 release notes");
        verify(requestSpec).options(searchOptionsCaptor.capture());
        verify(requestSpec, never()).tools(any(Object[].class));
        GoogleGenAiChatOptions searchOptions = searchOptionsCaptor.getValue().build();
        assertTrue(searchOptions.getGoogleSearchRetrieval());
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
        GenerateContentResponseUsageMetadata nativeUsage = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10_000).cachedContentTokenCount(8_000).candidatesTokenCount(400)
                .thoughtsTokenCount(200).totalTokenCount(10_600).build();
        DefaultUsage secondUsage = new DefaultUsage(10_000, 400, 10_600, nativeUsage);
        ChatResponse secondTurnResponse = buildChatResponse("Final answer after tool & retry", "STOP", secondUsage);
        when(settingsService.resolveModelConfig(anyString())).thenReturn(
                new GeminiModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash", "Desc", 1_048_576, 0.30, 0.075, 2.50));

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

        ArgumentCaptor<Object> publishedEvents = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(publishedEvents.capture());
        TokenUsageEvent usageEvent = null;
        List<AgentActivityEvent> activityEvents = new ArrayList<>();
        for (Object ev : publishedEvents.getAllValues()) {
            if (ev instanceof TokenUsageEvent tue) {
                usageEvent = tue;
            } else if (ev instanceof AgentActivityEvent aae) {
                activityEvents.add(aae);
            }
        }
        assertNotNull(usageEvent);
        assertTrue(activityEvents.stream()
                .anyMatch(e -> "qa:default".equals(e.conversationId()) && e.statusText().startsWith("Thinking with ")));
        assertTrue(activityEvents.stream().anyMatch(e -> "qa:default".equals(e.conversationId())
                && e.statusText().contains("backing off before retry (attempt 2/3)")));
        assertTrue(activityEvents.stream().anyMatch(e -> "qa:default".equals(e.conversationId())
                && e.statusText().contains("retrying model call (attempt 2/3)")));
        assertEquals("qa:default", usageEvent.conversationId());
        assertEquals(10_000, usageEvent.promptTokens());
        assertEquals(8_000, usageEvent.cachedInputTokens());
        assertEquals(2_000, usageEvent.uncachedInputTokens());
        assertEquals(400, usageEvent.candidatesTokens());
        assertEquals(200, usageEvent.thoughtsTokens());
        assertEquals(10_600, usageEvent.totalTokens());
        assertEquals(WorkflowPhase.EXPLORE, usageEvent.phase());
        assertEquals(0.0006, usageEvent.uncachedInputCostUsd(), 1e-6);
        assertEquals(0.0006, usageEvent.cachedInputCostUsd(), 1e-6);
        assertEquals(0.0010, usageEvent.outputCostUsd(), 1e-6);
        assertEquals(0.0005, usageEvent.thoughtsCostUsd(), 1e-6);
        assertEquals(0.0027, usageEvent.estimatedCostUsd(), 1e-6);

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
        ChatResponse emptyStopResponse = buildChatResponse("", "OTHER", null);
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

    @Test
    void testAdaptiveThinkingEscalatesOnCodeFailureDropsAfterPlanSubmitAndWaitsOnRateLimit() {
        ChatModel mockChatModel = mock(ChatModel.class);
        ToolCallingManager customToolManager = mock(ToolCallingManager.class);
        AiService serviceWithRealClient = createRealClientService(mockChatModel, customToolManager);
        serviceWithRealClient.setRateLimitCooldown(Duration.ofMillis(10));
        settings.setThinkingLevel("ADAPTIVE");
        when(promptService.buildSystemPrompt(any())).thenReturn("SYSTEM PROMPT");

        AssistantMessage toolCall1 = createToolCallMessage();
        ChatResponse call1Resp = createToolCallResponse(toolCall1);
        AssistantMessage toolCall2 = createToolCallMessage();
        ChatResponse call2Resp = createToolCallResponse(toolCall2);
        ChatResponse finalCodeResp = buildChatResponse("Code done", "STOP", null);

        List<GoogleGenAiThinkingLevel> capturedCodeThinkingLevels = new ArrayList<>();
        AtomicInteger codeCallCounter = new AtomicInteger();
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (p.getOptions() instanceof GoogleGenAiChatOptions genAiOpts) {
                capturedCodeThinkingLevels.add(genAiOpts.getThinkingLevel());
            }
            int callNum = codeCallCounter.incrementAndGet();
            if (callNum == 1) {
                return call1Resp;
            }
            if (callNum == 2) {
                throw new ClientException(429, "Too Many Requests", "RESOURCE_EXHAUSTED");
            }
            if (callNum == 3) {
                return call2Resp;
            }
            return finalCodeResp;
        });

        ToolResponseMessage failedToolMsg = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "executeJexl", """
                        {"success":false,"errorHint":"Compile failed"}"""))).build();
        ToolResponseMessage successToolMsg = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "executeJexl", """
                        {"success":true,"content":"OK"}"""))).build();

        AtomicInteger toolExecCounter = new AtomicInteger();
        when(customToolManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(inv -> {
            int execNum = toolExecCounter.incrementAndGet();
            ToolResponseMessage toolMsg;
            if (execNum == 1) {
                toolMsg = failedToolMsg;
            } else {
                toolMsg = successToolMsg;
            }
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("Implement feature"), toolCall1, toolMsg))
                    .returnDirect(false).build();
        });

        clearInvocations(eventPublisher);
        assertEquals("Code done",
                serviceWithRealClient.chat("task:code", WorkflowPhase.CODE, "Implement feature", null));
        assertEquals(List.of(GoogleGenAiThinkingLevel.LOW, GoogleGenAiThinkingLevel.HIGH, GoogleGenAiThinkingLevel.HIGH,
                GoogleGenAiThinkingLevel.LOW), capturedCodeThinkingLevels);

        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventsCaptor.capture());
        List<String> statusMessages = eventsCaptor.getAllValues().stream().filter(AgentActivityEvent.class::isInstance)
                .map(AgentActivityEvent.class::cast).map(AgentActivityEvent::statusText).toList();
        assertTrue(statusMessages.stream().anyMatch(s -> s.contains("Thinking with ") && s.contains("(Low)")));
        assertTrue(statusMessages.stream().anyMatch(s -> s.contains("Thinking with ") && s.contains("(High)")));
        assertTrue(statusMessages.stream().anyMatch(s -> s.contains("API rate limit (429) — waiting ")
                && s.contains("for quota window to reset (attempt 2/3)")));

        // Verify PLAN phase adaptive transition: HIGH before plan exists -> LOW after submitPlan
        List<GoogleGenAiThinkingLevel> capturedPlanThinkingLevels = new ArrayList<>();
        AtomicInteger planCallCounter = new AtomicInteger();
        when(planService.getCurrentPlan()).thenReturn(null);
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (p.getOptions() instanceof GoogleGenAiChatOptions genAiOpts) {
                capturedPlanThinkingLevels.add(genAiOpts.getThinkingLevel());
            }
            int callNum = planCallCounter.incrementAndGet();
            if (callNum == 1) {
                return call1Resp;
            }
            return buildChatResponse("Plan submitted", "STOP", null);
        });
        ImplementationPlan mockPlan = mock(ImplementationPlan.class);
        when(customToolManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(inv -> {
            when(planService.getCurrentPlan()).thenReturn(mockPlan);
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("Create plan"), toolCall1, successToolMsg))
                    .returnDirect(false).build();
        });

        assertEquals("Plan submitted",
                serviceWithRealClient.chat("task:plan", WorkflowPhase.PLAN, "Create plan", null));
        assertEquals(List.of(GoogleGenAiThinkingLevel.HIGH, GoogleGenAiThinkingLevel.LOW), capturedPlanThinkingLevels);
    }

    @Test
    void testNonRateLimitRetryRecreatesClientModelAndDowngradesThinkingLevelToLow() {
        ChatModel initialModel = mock(ChatModel.class);
        ChatModel recreatedModel = mock(ChatModel.class);
        lenient().when(recreatedModel.getOptions())
                .thenReturn(GoogleGenAiChatOptions.builder().model("gemini-3.1-pro").build());

        ToolCallingManager customToolManager = mock(ToolCallingManager.class);
        AiService service = createRealClientService(initialModel, customToolManager);
        when(promptService.buildSystemPrompt(WorkflowPhase.PLAN)).thenReturn("PLAN PROMPT");
        settings.setGeminiApiKey("valid-api-key");
        settings.setAgentProfile("NORMAL");

        AtomicInteger modelFactoryCalls = new AtomicInteger();
        service.setConnectionChatModelFactory(cfg -> {
            int count = modelFactoryCalls.incrementAndGet();
            if (count == 1) {
                return initialModel;
            }
            return recreatedModel;
        });
        service.reloadChatClient(AiConfig.GenAiConnectionConfig.forApiKey("valid-api-key"));
        assertEquals(1, modelFactoryCalls.get());

        List<GoogleGenAiThinkingLevel> initialModelThinking = new ArrayList<>();
        when(initialModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (p.getOptions() instanceof GoogleGenAiChatOptions opts) {
                initialModelThinking.add(opts.getThinkingLevel());
            }
            throw new ServerException(503, "Service Unavailable", "UNAVAILABLE");
        });

        List<GoogleGenAiThinkingLevel> recreatedModelThinking = new ArrayList<>();
        when(recreatedModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (p.getOptions() instanceof GoogleGenAiChatOptions opts) {
                recreatedModelThinking.add(opts.getThinkingLevel());
            }
            return buildChatResponse("Recovered on fresh client with LOW thinking", "STOP", null);
        });

        clearInvocations(eventPublisher);
        String response = service.chat("task:retry-reset", WorkflowPhase.PLAN, "Design plan", null);
        assertEquals("Recovered on fresh client with LOW thinking", response);
        assertEquals(2, modelFactoryCalls.get());
        assertEquals(List.of(GoogleGenAiThinkingLevel.HIGH), initialModelThinking);
        assertEquals(List.of(GoogleGenAiThinkingLevel.LOW), recreatedModelThinking);

        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventsCaptor.capture());
        List<String> statusMessages = eventsCaptor.getAllValues().stream().filter(AgentActivityEvent.class::isInstance)
                .map(AgentActivityEvent.class::cast).map(AgentActivityEvent::statusText).toList();
        assertTrue(statusMessages.stream()
                .anyMatch(s -> s.contains("resetting client connection before retry (attempt 2/3)")));
    }

    @Test
    void testAutoContinueSelfHealingRecoversAfterToolProgressWhenFastRetriesExhausted() {
        ChatModel firstClientModel = mock(ChatModel.class);
        ChatModel retryClientModel1 = mock(ChatModel.class);
        ChatModel retryClientModel2 = mock(ChatModel.class);
        ChatModel autoContinueClientModel = mock(ChatModel.class);
        for (ChatModel m : List.of(firstClientModel, retryClientModel1, retryClientModel2, autoContinueClientModel)) {
            lenient().when(m.getOptions())
                    .thenReturn(GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build());
        }

        ToolCallingManager customToolManager = mock(ToolCallingManager.class);
        ChatMemory realMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        AiService service = new AiService(ChatClient.builder(firstClientModel), settingsService, null, promptService,
                jexlTool, planService, realMemory, customToolManager, eventPublisher);
        service.setRetryTemplate(AiConfig.createRetryTemplate(3, Duration.ofMillis(5), 2.0), 3);
        settings.setGeminiApiKey("valid-api-key");

        List<ChatModel> modelSequence = List.of(firstClientModel, retryClientModel1, retryClientModel2,
                autoContinueClientModel);
        AtomicInteger factoryIndex = new AtomicInteger(0);
        service.setConnectionChatModelFactory(cfg -> {
            int idx = Math.min(factoryIndex.getAndIncrement(), modelSequence.size() - 1);
            return modelSequence.get(idx);
        });
        service.reloadChatClient(AiConfig.GenAiConnectionConfig.forApiKey("valid-api-key"));

        when(promptService.buildSystemPrompt(WorkflowPhase.CODE)).thenReturn("INITIAL SYSTEM PROMPT",
                "REFRESHED SYSTEM PROMPT WITH UPDATED PLAN");

        AssistantMessage toolCallMsg = createToolCallMessage();
        ChatResponse firstTurnToolCallResp = createToolCallResponse(toolCallMsg);
        ToolResponseMessage successToolMsg = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "executeJexl", """
                        {"success":true,"content":"Step 0 completed"}"""))).build();

        AtomicInteger firstModelCalls = new AtomicInteger();
        when(firstClientModel.call(any(Prompt.class))).thenAnswer(inv -> {
            if (firstModelCalls.incrementAndGet() == 1) {
                return firstTurnToolCallResp;
            }
            throw new ServerException(500, "Internal Server Error", "INTERNAL");
        });
        when(retryClientModel1.call(any(Prompt.class)))
                .thenThrow(new ServerException(500, "Internal Server Error", "INTERNAL"));
        when(retryClientModel2.call(any(Prompt.class)))
                .thenThrow(new ServerException(500, "Internal Server Error", "INTERNAL"));

        List<Prompt> autoContinuePrompts = new ArrayList<>();
        when(autoContinueClientModel.call(any(Prompt.class))).thenAnswer(inv -> {
            autoContinuePrompts.add(inv.getArgument(0));
            return buildChatResponse("Completed remaining steps after auto-continue!", "STOP", null);
        });

        when(customToolManager.executeToolCalls(any(Prompt.class), eq(firstTurnToolCallResp)))
                .thenReturn(DefaultToolExecutionResult.builder()
                        .conversationHistory(List.of(new UserMessage("Implement feature"), toolCallMsg, successToolMsg))
                        .returnDirect(false).build());

        clearInvocations(eventPublisher);
        String result = service.chat("task:auto-continue", WorkflowPhase.CODE, "Implement feature", null);
        assertEquals("Completed remaining steps after auto-continue!", result);
        assertEquals(1, autoContinuePrompts.size());
        Prompt recoveryPrompt = autoContinuePrompts.get(0);
        assertEquals("REFRESHED SYSTEM PROMPT WITH UPDATED PLAN", recoveryPrompt.getSystemMessage().getText());
        assertTrue(recoveryPrompt.getInstructions().stream().anyMatch(
                m -> m instanceof UserMessage && m.getText().contains("interrupted by a transient network/server")));

        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventsCaptor.capture());
        List<String> statusMessages = eventsCaptor.getAllValues().stream().filter(AgentActivityEvent.class::isInstance)
                .map(AgentActivityEvent.class::cast).map(AgentActivityEvent::statusText).toList();
        assertTrue(statusMessages.stream()
                .anyMatch(s -> s.contains(AiService.AUTO_CONTINUE_ACTIVITY_MESSAGE) && s.contains("(attempt 1/2)")));

        // Verify that when 0 tool turns occurred, transient failure after fast retries is NOT auto-continued
        ChatModel alwaysFailingModel = mock(ChatModel.class);
        lenient().when(alwaysFailingModel.getOptions())
                .thenReturn(GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build());
        when(alwaysFailingModel.call(any(Prompt.class)))
                .thenThrow(new ServerException(503, "Service Unavailable", "UNAVAILABLE"));
        service.setConnectionChatModelFactory(cfg -> alwaysFailingModel);
        service.reloadChatClient(AiConfig.GenAiConnectionConfig.forApiKey("valid-api-key"));

        assertThrows(ServerException.class,
                () -> service.chat("task:no-progress", WorkflowPhase.CODE, "Fails immediately", null));
        verify(alwaysFailingModel, times(3)).call(any(Prompt.class));
    }
}
