package org.roxycode.app.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.AiConfig.GenAiConnectionConfig;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

/**
 * Orchestrates AI chat interactions, model selection, and tool execution.
 */
@Service
public class AiService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String AGENT_NAME = "Roxy";

    public static final int MAX_AUTO_NUDGES = 2;
    public static final int TEST_CONNECTION_TIMEOUT_MS = 15_000;
    public static final String AUTO_NUDGE_PROMPT = "You completed the turn without returning any text response. Please summarize what you just did or provide your final response to the user.";
    static final String GROUNDED_SEARCH_SYSTEM_PROMPT = "You are a technical research assistant. Use Google Search to provide accurate, up-to-date facts, synthesis, and source URLs or citations when available.";
    private static final List<String> TOOL_FAILURE_MARKERS = List.of("""
            "success":false""", """
            "success": false""", """
            "error":""");

    @FunctionalInterface
    interface ConnectionConfigTester {
        String verifyConnection(GenAiConnectionConfig config, String modelName) throws Exception;
    }

    private volatile ChatClient chatClient;
    private final SettingsService settingsService;
    private final ProjectService projectService;
    private final PromptService promptService;
    private final JexlTool jexlTool;
    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMemory chatMemory;
    private final ToolCallingManager toolCallingManager;
    private volatile GenAiConnectionConfig activeConnectionConfig;
    private volatile Client managedClient;
    private Function<GenAiConnectionConfig, ChatClient.Builder> connectionChatClientBuilderFactory = this::createChatClientBuilderForConnection;
    private ConnectionConfigTester connectionTester = this::executeConnectionTest;
    private volatile RetryTemplate retryTemplate = AiConfig.createRetryTemplate();
    private volatile int maxRetryAttempts = AiConfig.DEFAULT_MAX_RETRY_ATTEMPTS;
    private volatile Duration rateLimitCooldown = AiConfig.DEFAULT_RATE_LIMIT_COOLDOWN;

    /**
     * Constructs a new AiService.
     *
     * @param chatClientBuilder
     *            the chat client builder
     * @param settingsService
     *            the settings service
     * @param projectService
     *            the project service
     * @param promptService
     *            the prompt service
     * @param jexlTool
     *            the JEXL tool
     * @param planService
     *            the plan service
     * @param chatMemory
     *            the chat memory
     * @param toolCallingManager
     *            the tool calling manager
     * @param eventPublisher
     *            the event publisher
     */
    @Autowired
    public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService,
            @Nullable ProjectService projectService, PromptService promptService, JexlTool jexlTool,
            PlanService planService, ChatMemory chatMemory, ToolCallingManager toolCallingManager,
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.promptService = promptService;
        this.jexlTool = jexlTool;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.chatMemory = chatMemory;
        this.toolCallingManager = toolCallingManager;
        this.activeConnectionConfig = resolveConfiguredConnection();
        this.chatClient = buildChatClient(chatClientBuilder);
    }

    private ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder.defaultAdvisors(new ModelCallRetryAdvisor()).build();
    }

    private GenAiConnectionConfig resolveConfiguredConnection() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return GenAiConnectionConfig.fromSettings(settingsService.getSettings());
        }
        return GenAiConnectionConfig.forApiKey("");
    }

    @SuppressWarnings("PMD.CloseResource")
    private ChatClient.Builder createChatClientBuilderForConnection(GenAiConnectionConfig config) {
        Client oldClient = this.managedClient;
        Client newClient = AiConfig.createGenAiClient(config, AiConfig.DEFAULT_HTTP_TIMEOUT_MS);
        this.managedClient = newClient;
        closeClientQuietly(oldClient);
        GoogleGenAiChatModel chatModel = AiConfig.createChatModel(newClient, resolveEffectiveGeminiModel(),
                toolCallingManager);
        return ChatClient.builder(chatModel);
    }

    private static void closeClientQuietly(Client client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Failed to close Google GenAI Client: {}", e.getMessage());
            }
        }
    }

    /**
     * Closes the service and releases any managed resources.
     */
    @Override
    @SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
    public synchronized void close() {
        Client clientToClose = this.managedClient;
        this.managedClient = null;
        closeClientQuietly(clientToClose);
    }

    /**
     * Handles settings changed events to reload the AI client if necessary.
     *
     * @param event
     *            the settings changed event
     */
    @EventListener
    public synchronized void onSettingsChanged(SettingsChangedEvent event) {
        GenAiConnectionConfig newConfig;
        if (event != null && event.settings() != null) {
            newConfig = GenAiConnectionConfig.fromSettings(event.settings());
        } else {
            newConfig = resolveConfiguredConnection();
        }
        if (Objects.equals(newConfig, this.activeConnectionConfig)) {
            return;
        }
        if (!newConfig.isConfigured()) {
            this.activeConnectionConfig = newConfig;
            close();
            return;
        }
        reloadChatClient(newConfig);
    }

    synchronized void reloadChatClient(GenAiConnectionConfig config) {
        if (config == null || !config.isConfigured()) {
            return;
        }
        ChatClient.Builder builder = connectionChatClientBuilderFactory.apply(config);
        this.chatClient = buildChatClient(builder);
        this.activeConnectionConfig = config;
        if (config.vertexAi()) {
            log.info("Hot-reloaded Gemini ChatClient with Vertex AI configuration (project={}, location={})",
                    config.projectId(), config.location());
        } else {
            log.info("Hot-reloaded Gemini ChatClient with updated API key");
        }
    }

    /**
     * Tests the connection to Google GenAI (Gemini API Key or Vertex AI) using the provided connection configuration
     * and model name.
     *
     * @param config
     *            the connection configuration to test
     * @param modelName
     *            the name of the model to test (optional, defaults to configured model)
     * @return the result of the connection test
     */
    public String testConnection(GenAiConnectionConfig config, String modelName) {
        if (config == null) {
            throw new IllegalArgumentException("Connection configuration must not be null.");
        }
        if (config.vertexAi()) {
            if (config.projectId().isEmpty()) {
                throw new IllegalArgumentException("Please enter a GCP Project ID first.");
            }
            if (config.location().isEmpty()) {
                throw new IllegalArgumentException("Please enter a Vertex Location first.");
            }
        } else if (config.apiKey().isEmpty()) {
            throw new IllegalArgumentException("Please enter a Gemini API Key first.");
        }
        String effectiveModel = resolveEffectiveGeminiModel();
        if (StringUtils.isNotBlank(modelName)) {
            effectiveModel = modelName.trim();
        }
        try {
            return connectionTester.verifyConnection(config, effectiveModel);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Connection test failed: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private String executeConnectionTest(GenAiConnectionConfig config, String modelName) {
        try (Client testClient = AiConfig.createGenAiClient(config, TEST_CONNECTION_TIMEOUT_MS)) {
            GoogleGenAiChatModel testModel = AiConfig.createChatModel(testClient, modelName, toolCallingManager);
            ChatResponse response = testModel
                    .call(new Prompt("Reply with OK", GoogleGenAiChatOptions.builder().model(modelName).build()));
            return StringUtils.defaultIfBlank(ChatResponseFormatter.extractResponseText(response), "OK");
        }
    }

    void setConnectionChatClientBuilderFactory(Function<GenAiConnectionConfig, ChatClient.Builder> factory) {
        this.connectionChatClientBuilderFactory = Objects.requireNonNull(factory, "factory must not be null");
    }

    void setConnectionConfigTester(ConnectionConfigTester connectionTester) {
        this.connectionTester = Objects.requireNonNull(connectionTester, "connectionTester must not be null");
    }

    GenAiConnectionConfig getActiveConnectionConfig() {
        return activeConnectionConfig;
    }

    String resolveEffectiveGeminiModel() {
        return ProjectService.resolveEffectiveGeminiModel(projectService, settingsService);
    }

    ThinkingLevel resolveEffectiveThinkingLevel() {
        return ProjectService.resolveEffectiveThinkingLevel(projectService, settingsService);
    }

    void setRetryTemplate(RetryTemplate retryTemplate) {
        setRetryTemplate(retryTemplate, AiConfig.DEFAULT_MAX_RETRY_ATTEMPTS);
    }

    void setRetryTemplate(RetryTemplate retryTemplate, int maxRetryAttempts) {
        this.retryTemplate = Objects.requireNonNull(retryTemplate, "retryTemplate must not be null");
        this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
    }

    void setRateLimitCooldown(Duration rateLimitCooldown) {
        this.rateLimitCooldown = Objects.requireNonNull(rateLimitCooldown, "rateLimitCooldown must not be null");
    }

    /**
     * Executes a chat turn with the AI model, including prompt construction, model call, and tool execution
     * orchestration.
     *
     * @param conversationId
     *            the unique identifier for the conversation
     * @param phase
     *            the current workflow phase (defaults to active plan phase if null)
     * @param message
     *            the user message to process
     * @param images
     *            optional list of images to include in the prompt
     * @return the formatted AI response content
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public String chat(String conversationId, WorkflowPhase phase, String message, List<byte[]> images) {
        ExceptionUtils.checkInterrupted("Chat turn interrupted before start");
        WorkflowPhase resolvedPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        TurnToolContext turnContext = new TurnToolContext(conversationId, resolvedPhase, new AtomicInteger(0));
        String convId = turnContext.conversationId();
        try {
            ChatClient.ChatClientRequestSpec promptSpec = buildPrompt(turnContext, message, images);
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            ChatResponse chatResponse = executeChatCall(promptSpec, convId, resolvedPhase);
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
            String content = ChatResponseFormatter.formatChatResponse(chatResponse, turnContext);
            publishTurnComplete(convId, turnContext.turnCounter().get(), content);
            return content;
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Chat turn cancelled");
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected error during chat turn", e);
        }
    }

    /**
     * Performs a grounded web search using Google Search retrieval to look up live documentation and facts.
     *
     * @param query
     *            the search query or question
     * @return the synthesized answer with grounded facts and citations
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public String groundedSearch(String query) {
        String trimmedQuery = StringUtils.trimToEmpty(query);
        if (trimmedQuery.isEmpty()) {
            throw new IllegalArgumentException("Search query must not be blank.");
        }
        ExceptionUtils.checkInterrupted("Web search interrupted before start");
        GoogleGenAiChatOptions.Builder optionsBuilder = GoogleGenAiChatOptions.builder()
                .model(resolveEffectiveGeminiModel()).googleSearchRetrieval(true);
        ThinkingLevel effectiveThinking = resolveEffectiveThinkingLevel();
        if (effectiveThinking == ThinkingLevel.ADAPTIVE) {
            ThinkingLevel.LOW.applyTo(optionsBuilder);
        } else {
            effectiveThinking.applyTo(optionsBuilder);
        }
        try {
            ChatResponse chatResponse = chatClient.prompt().system(GROUNDED_SEARCH_SYSTEM_PROMPT).user(trimmedQuery)
                    .options(optionsBuilder).call().chatResponse();
            ExceptionUtils.checkInterrupted("Web search interrupted after model call");
            return StringUtils.trimToEmpty(ChatResponseFormatter.extractResponseText(chatResponse));
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Web search cancelled");
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected error during grounded web search", e);
        }
    }

    private ChatResponse executeChatCall(ChatClient.ChatClientRequestSpec promptSpec, String convId,
            WorkflowPhase phase) {
        return promptSpec.advisors(a -> {
            a.param(ChatMemory.CONVERSATION_ID, convId);
            if (phase != null) {
                a.param(TurnToolContext.PHASE_KEY, phase);
            }
        }).call().chatResponse();
    }

    private final class ModelCallRetryAdvisor implements CallAdvisor {
        @Override
        public String getName() {
            return "ModelCallRetryAdvisor";
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE - 1;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
            String conversationId = resolveConversationId(chatClientRequest);
            String modelName = resolveRequestModelName(chatClientRequest);
            WorkflowPhase currentPhase = resolveWorkflowPhase(chatClientRequest);
            ThinkingLevel configuredLevel = resolveEffectiveThinkingLevel();
            ThinkingLevel activeSubTurnLevel = resolveActiveSubTurnThinkingLevel(configuredLevel, currentPhase,
                    chatClientRequest);
            ChatClientRequest effectiveRequest = applySubTurnThinkingLevel(chatClientRequest, configuredLevel,
                    activeSubTurnLevel);
            String thinkingActivityMessage = formatThinkingActivityMessage(modelName, configuredLevel,
                    activeSubTurnLevel);
            int maxAttempts = maxRetryAttempts;
            AtomicInteger attemptCounter = new AtomicInteger(0);
            ChatClientResponse response = RetryUtils.execute(retryTemplate, () -> {
                ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
                int retryCount = attemptCounter.getAndIncrement();
                if (retryCount == 0) {
                    publishActivity(conversationId, thinkingActivityMessage);
                } else {
                    publishActivity(conversationId, "Transient API error — retrying model call"
                            + formatRetryAttemptSuffix(retryCount + 1, maxAttempts));
                }
                try {
                    return callAdvisorChain.copy(this).nextCall(effectiveRequest);
                } catch (RuntimeException ex) {
                    if (AiConfig.isTransientAiError(ex) && (retryCount + 1) < maxAttempts) {
                        int nextAttempt = retryCount + 2;
                        if (AiConfig.isRateLimitError(ex)) {
                            waitForRateLimitCooldown(conversationId, nextAttempt, maxAttempts);
                        } else {
                            publishActivity(conversationId, "Transient API error — backing off before retry"
                                    + formatRetryAttemptSuffix(nextAttempt, maxAttempts));
                        }
                    }
                    throw ex;
                }
            });
            if (response != null) {
                publishTokenUsageIfPresent(conversationId, modelName, currentPhase, response.chatResponse());
            }
            return response;
        }
    }

    private void waitForRateLimitCooldown(String conversationId, int nextAttempt, int maxAttempts) {
        Duration cooldown = rateLimitCooldown;
        long cooldownSeconds = 60L;
        if (cooldown != null && !cooldown.isNegative() && !cooldown.isZero()) {
            cooldownSeconds = Math.max(1L, cooldown.toSeconds());
        }
        publishActivity(conversationId, "API rate limit (429) — waiting " + cooldownSeconds
                + "s for quota window to reset" + formatRetryAttemptSuffix(nextAttempt, maxAttempts));
        if (cooldown != null && !cooldown.isNegative() && !cooldown.isZero()) {
            try {
                Thread.sleep(cooldown.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ExceptionUtils.asCancellationException(ie, "Rate-limit cooldown interrupted");
            }
        }
    }

    ThinkingLevel resolveActiveSubTurnThinkingLevel(ThinkingLevel configuredLevel, @Nullable WorkflowPhase phase,
            @Nullable ChatClientRequest request) {
        ThinkingLevel resolvedConfigured = Objects.requireNonNullElse(configuredLevel, ThinkingLevel.DEFAULT);
        if (resolvedConfigured != ThinkingLevel.ADAPTIVE) {
            return resolvedConfigured;
        }
        boolean planSubmitted = planService != null && planService.getCurrentPlan() != null;
        ToolResponseState toolState = inspectPromptToolResponses(request);
        return resolvedConfigured.resolveSubTurnLevel(phase, planSubmitted, toolState.hasToolResponses(),
                toolState.lastToolCallFailed());
    }

    private record ToolResponseState(boolean hasToolResponses, boolean lastToolCallFailed) {
    }

    private static ToolResponseState inspectPromptToolResponses(@Nullable ChatClientRequest request) {
        if (request == null || request.prompt() == null || request.prompt().getInstructions() == null) {
            return new ToolResponseState(false, false);
        }
        List<Message> instructions = request.prompt().getInstructions();
        ToolResponseMessage lastToolMessage = null;
        for (Message msg : instructions) {
            if (msg instanceof ToolResponseMessage toolMsg) {
                lastToolMessage = toolMsg;
            }
        }
        if (lastToolMessage == null) {
            return new ToolResponseState(false, false);
        }
        boolean failed = containsToolFailure(lastToolMessage);
        return new ToolResponseState(true, failed);
    }

    static boolean containsToolFailure(@Nullable ToolResponseMessage toolResponseMessage) {
        if (toolResponseMessage == null || toolResponseMessage.getResponses() == null) {
            return false;
        }
        for (ToolResponseMessage.ToolResponse resp : toolResponseMessage.getResponses()) {
            if (resp != null && containsToolFailureText(resp.responseData())) {
                return true;
            }
        }
        return false;
    }

    static boolean containsToolFailureText(@Nullable String responseData) {
        if (StringUtils.isBlank(responseData)) {
            return false;
        }
        for (String marker : TOOL_FAILURE_MARKERS) {
            if (StringUtils.containsIgnoreCase(responseData, marker)) {
                return true;
            }
        }
        return false;
    }

    private static ChatClientRequest applySubTurnThinkingLevel(ChatClientRequest request, ThinkingLevel configuredLevel,
            ThinkingLevel activeSubTurnLevel) {
        if (configuredLevel != ThinkingLevel.ADAPTIVE || request == null || request.prompt() == null) {
            return request;
        }
        ChatOptions options = request.prompt().getOptions();
        if (options instanceof GoogleGenAiChatOptions genAiOptions) {
            GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder().model(genAiOptions.getModel())
                    .thinkingLevel(activeSubTurnLevel.getGenAiThinkingLevel());
            if (genAiOptions.getToolCallbacks() != null) {
                builder.toolCallbacks(genAiOptions.getToolCallbacks());
            }
            if (genAiOptions.getToolContext() != null) {
                builder.toolContext(genAiOptions.getToolContext());
            }
            if (genAiOptions.getGoogleSearchRetrieval() != null) {
                builder.googleSearchRetrieval(genAiOptions.getGoogleSearchRetrieval());
            }
            Prompt updatedPrompt = request.prompt().mutate().chatOptions(builder.build()).build();
            return request.mutate().prompt(updatedPrompt).build();
        }
        return request;
    }

    private static final String ACTIVITY_ELLIPSIS_SUFFIX = ")...";

    private static String formatRetryAttemptSuffix(int attempt, int maxAttempts) {
        return " (attempt " + attempt + "/" + maxAttempts + ACTIVITY_ELLIPSIS_SUFFIX;
    }

    private static String formatThinkingActivityMessage(String modelName, ThinkingLevel configuredLevel,
            ThinkingLevel activeSubTurnLevel) {
        if (configuredLevel == ThinkingLevel.ADAPTIVE && activeSubTurnLevel != null
                && activeSubTurnLevel != ThinkingLevel.DEFAULT) {
            return "Thinking with " + modelName + " (" + activeSubTurnLevel.getDisplayName() + ACTIVITY_ELLIPSIS_SUFFIX;
        }
        return "Thinking with " + modelName + "...";
    }

    private void publishActivity(String conversationId, String statusText) {
        if (eventPublisher != null && StringUtils.isNotBlank(statusText)) {
            eventPublisher.publishEvent(new AgentActivityEvent(StringUtils.defaultString(conversationId), statusText));
        }
    }

    private static String resolveConversationId(ChatClientRequest request) {
        if (request != null && request.context() != null) {
            Object convId = request.context().get(ChatMemory.CONVERSATION_ID);
            if (convId != null) {
                return convId.toString();
            }
        }
        return "";
    }

    @Nullable
    private WorkflowPhase resolveWorkflowPhase(ChatClientRequest request) {
        if (request != null && request.context() != null) {
            WorkflowPhase resolved = WorkflowPhase.fromObject(request.context().get(TurnToolContext.PHASE_KEY), null);
            if (resolved != null) {
                return resolved;
            }
        }
        if (planService != null) {
            return planService.getCurrentPhase();
        }
        return null;
    }

    private String resolveRequestModelName(ChatClientRequest request) {
        if (request != null && request.prompt() != null && request.prompt().getOptions() != null) {
            String optionModel = request.prompt().getOptions().getModel();
            if (StringUtils.isNotBlank(optionModel)) {
                return optionModel.trim();
            }
        }
        return resolveEffectiveGeminiModel();
    }

    private record ExtractedTokenCounts(int promptTokens, int cachedInputTokens, int uncachedInputTokens,
            int candidatesTokens, int thoughtsTokens, int totalTokens) {
    }

    void publishTokenUsageIfPresent(String conversationId, String modelName, @Nullable WorkflowPhase phase,
            @Nullable ChatResponse chatResponse) {
        if (eventPublisher == null || chatResponse == null || chatResponse.getMetadata() == null) {
            return;
        }
        ExtractedTokenCounts counts = extractTokenCounts(chatResponse.getMetadata().getUsage());
        if (counts == null) {
            return;
        }

        String resolvedModel = StringUtils.defaultIfBlank(modelName, resolveEffectiveGeminiModel()).trim();
        GeminiModelConfig modelConfig = SettingsService.resolveModelConfig(settingsService, resolvedModel);

        double uncachedInputCostUsd = modelConfig.calculateUncachedInputCostUsd(counts.uncachedInputTokens());
        double cachedInputCostUsd = modelConfig.calculateCachedInputCostUsd(counts.cachedInputTokens());
        double outputCostUsd = modelConfig.calculateOutputCostUsd(counts.candidatesTokens());
        double thoughtsCostUsd = modelConfig.calculateOutputCostUsd(counts.thoughtsTokens());
        double costUsd = uncachedInputCostUsd + cachedInputCostUsd + outputCostUsd + thoughtsCostUsd;
        eventPublisher.publishEvent(new TokenUsageEvent(StringUtils.defaultString(conversationId), resolvedModel,
                modelConfig.contextWindowTokens(), counts.promptTokens(), counts.cachedInputTokens(),
                counts.uncachedInputTokens(), counts.candidatesTokens(), counts.thoughtsTokens(), counts.totalTokens(),
                phase, uncachedInputCostUsd, cachedInputCostUsd, outputCostUsd, thoughtsCostUsd, costUsd));
    }

    @Nullable
    private static ExtractedTokenCounts extractTokenCounts(@Nullable Usage usage) {
        if (usage == null) {
            return null;
        }
        int promptTokens = ChatResponseFormatter.safeNonNegativeInt(usage.getPromptTokens());
        int candidatesTokens = ChatResponseFormatter.safeNonNegativeInt(usage.getCompletionTokens());
        int totalTokens = ChatResponseFormatter.safeNonNegativeInt(usage.getTotalTokens());
        int cachedTokens = 0;
        int thoughtsTokens = 0;

        if (usage.getNativeUsage() instanceof GenerateContentResponseUsageMetadata genAiUsage) {
            cachedTokens = ChatResponseFormatter.safeNonNegativeInt(genAiUsage.cachedContentTokenCount().orElse(0));
            thoughtsTokens = ChatResponseFormatter.safeNonNegativeInt(genAiUsage.thoughtsTokenCount().orElse(0));
            if (promptTokens == 0) {
                promptTokens = ChatResponseFormatter.safeNonNegativeInt(genAiUsage.promptTokenCount().orElse(0));
            }
            if (candidatesTokens == 0) {
                candidatesTokens = ChatResponseFormatter
                        .safeNonNegativeInt(genAiUsage.candidatesTokenCount().orElse(0));
            }
            if (totalTokens == 0) {
                totalTokens = ChatResponseFormatter.safeNonNegativeInt(genAiUsage.totalTokenCount().orElse(0));
            }
        }

        int cachedInputTokens = Math.min(promptTokens, cachedTokens);
        int uncachedInputTokens = Math.max(0, promptTokens - cachedInputTokens);
        if (totalTokens <= 0) {
            totalTokens = promptTokens + candidatesTokens + thoughtsTokens;
        }
        if (promptTokens <= 0 && candidatesTokens <= 0 && thoughtsTokens <= 0 && totalTokens <= 0) {
            return null;
        }
        return new ExtractedTokenCounts(promptTokens, cachedInputTokens, uncachedInputTokens, candidatesTokens,
                thoughtsTokens, totalTokens);
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(TurnToolContext turnContext, String message,
            List<byte[]> images) {
        WorkflowPhase currentPhase = turnContext.phase();
        GoogleGenAiChatOptions.Builder optionsBuilder = GoogleGenAiChatOptions.builder()
                .model(resolveEffectiveGeminiModel());
        resolveEffectiveThinkingLevel().applyTo(optionsBuilder);
        ChatClient.ChatClientRequestSpec promptSpec = chatClient.prompt()
                .system(promptService.buildSystemPrompt(currentPhase)).user(u -> {
                    u.text(message);
                    if (images != null) {
                        for (byte[] img : images) {
                            u.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(img));
                        }
                    }
                }).options(optionsBuilder);
        promptSpec.tools(jexlTool);
        promptSpec.advisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build(),
                new AutoNudgeAdvisor(turnContext, chatMemory));
        return promptSpec.toolContext(turnContext.toMap());
    }

    private void publishTurnComplete(String conversationId, int totalTurns, String result) {
        eventPublisher.publishEvent(new AgentTurnCompleteEvent(conversationId, AGENT_NAME, totalTurns, result));
    }
}
