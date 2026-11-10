package org.roxycode.app.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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

    @FunctionalInterface
    interface ConnectionTester {
        String verifyConnection(String apiKey, String modelName) throws Exception;
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
    private volatile String activeApiKey;
    private volatile Client managedClient;
    private Function<String, ChatClient.Builder> chatClientBuilderFactory = this::createChatClientBuilderForApiKey;
    private ConnectionTester connectionTester = this::executeConnectionTest;
    private volatile RetryTemplate retryTemplate = AiConfig.createRetryTemplate();
    private volatile int maxRetryAttempts = AiConfig.DEFAULT_MAX_RETRY_ATTEMPTS;

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
        this.activeApiKey = resolveConfiguredApiKey();
        this.chatClient = buildChatClient(chatClientBuilder);
    }

    private ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder.defaultAdvisors(new ModelCallRetryAdvisor()).build();
    }

    private String resolveConfiguredApiKey() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return StringUtils.trimToEmpty(settingsService.getSettings().getGeminiApiKey());
        }
        return "";
    }

    @SuppressWarnings("PMD.CloseResource")
    private ChatClient.Builder createChatClientBuilderForApiKey(String apiKey) {
        Client oldClient = this.managedClient;
        Client newClient = AiConfig.createGenAiClient(apiKey, AiConfig.DEFAULT_HTTP_TIMEOUT_MS);
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
        String newApiKey;
        if (event != null && event.settings() != null) {
            newApiKey = event.settings().getGeminiApiKey();
        } else {
            newApiKey = resolveConfiguredApiKey();
        }
        String trimmedKey = StringUtils.trimToEmpty(newApiKey);
        if (Objects.equals(trimmedKey, this.activeApiKey)) {
            return;
        }
        if (trimmedKey.isEmpty()) {
            this.activeApiKey = "";
            close();
            return;
        }
        reloadChatClient(trimmedKey);
    }

    synchronized void reloadChatClient(String apiKey) {
        String trimmed = StringUtils.trimToEmpty(apiKey);
        if (trimmed.isEmpty()) {
            return;
        }
        ChatClient.Builder builder = chatClientBuilderFactory.apply(trimmed);
        this.chatClient = buildChatClient(builder);
        this.activeApiKey = trimmed;
        log.info("Hot-reloaded Gemini ChatClient with updated API key");
    }

    /**
     * Tests the connection to the Gemini API using the provided API key and model name.
     *
     * @param apiKey
     *            the Gemini API key to test
     * @param modelName
     *            the name of the model to test (optional, defaults to configured model)
     * @return the result of the connection test (typically the model name if successful)
     */
    public String testConnection(String apiKey, String modelName) {
        String trimmedKey = StringUtils.trimToEmpty(apiKey);
        if (trimmedKey.isEmpty()) {
            throw new IllegalArgumentException("Please enter a Gemini API Key first.");
        }
        String effectiveModel = resolveEffectiveGeminiModel();
        if (StringUtils.isNotBlank(modelName)) {
            effectiveModel = modelName.trim();
        }
        try {
            return connectionTester.verifyConnection(trimmedKey, effectiveModel);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Connection test failed: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private String executeConnectionTest(String apiKey, String modelName) {
        try (Client testClient = AiConfig.createGenAiClient(apiKey, TEST_CONNECTION_TIMEOUT_MS)) {
            GoogleGenAiChatModel testModel = AiConfig.createChatModel(testClient, modelName, toolCallingManager);
            ChatResponse response = testModel
                    .call(new Prompt("Reply with OK", GoogleGenAiChatOptions.builder().model(modelName).build()));
            return StringUtils.defaultIfBlank(ChatResponseFormatter.extractResponseText(response), "OK");
        }
    }

    void setChatClientBuilderFactory(Function<String, ChatClient.Builder> factory) {
        this.chatClientBuilderFactory = Objects.requireNonNull(factory, "factory must not be null");
    }

    void setConnectionTester(ConnectionTester connectionTester) {
        this.connectionTester = Objects.requireNonNull(connectionTester, "connectionTester must not be null");
    }

    String getActiveApiKey() {
        return activeApiKey;
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
        resolveEffectiveThinkingLevel().applyTo(optionsBuilder);
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
            int maxAttempts = maxRetryAttempts;
            AtomicInteger attemptCounter = new AtomicInteger(0);
            ChatClientResponse response = RetryUtils.execute(retryTemplate, () -> {
                ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
                int retryCount = attemptCounter.getAndIncrement();
                if (retryCount == 0) {
                    publishActivity(conversationId, "Thinking with " + modelName + "...");
                } else {
                    publishActivity(conversationId, "Transient API error — retrying model call (attempt "
                            + (retryCount + 1) + "/" + maxAttempts + ")...");
                }
                try {
                    return callAdvisorChain.copy(this).nextCall(chatClientRequest);
                } catch (RuntimeException ex) {
                    if (AiConfig.isTransientAiError(ex) && (retryCount + 1) < maxAttempts) {
                        publishActivity(conversationId, "Transient API error — backing off before retry (attempt "
                                + (retryCount + 2) + "/" + maxAttempts + ")...");
                    }
                    throw ex;
                }
            });
            if (response != null) {
                publishTokenUsageIfPresent(conversationId, modelName, resolveWorkflowPhase(chatClientRequest),
                        response.chatResponse());
            }
            return response;
        }
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
