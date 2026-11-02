package org.roxycode.app.service;

import com.google.genai.Client;
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
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.SettingsChangedEvent;
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
import org.springframework.ai.chat.model.Generation;
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

@Service
public class AiService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String AGENT_NAME = "Roxy";
    static final String FINISH_REASON_UNKNOWN = "UNKNOWN";

    public static final String TRUNCATED_MARKER = "⚠️ **Response Truncated (finishReason:";
    public static final String NO_VISIBLE_RESPONSE_MARKER = "⚠️ **No Visible Response (finishReason:";
    public static final String EMPTY_RESPONSE_MARKER = "⚠️ **Empty Response Returned (finishReason:";
    public static final String EMPTY_STOP_MESSAGE = "Roxy completed the turn without generating a visible text message.";

    public static final int MAX_AUTO_NUDGES = 2;
    public static final int TEST_CONNECTION_TIMEOUT_MS = 15_000;
    public static final String AUTO_NUDGE_PROMPT = "You completed the turn without returning any text response. Please summarize what you just did or provide your final response to the user.";

    @FunctionalInterface
    public interface ConnectionTester {
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

    public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService, PromptService promptService,
            JexlTool jexlTool, PlanService planService, ChatMemory chatMemory, ToolCallingManager toolCallingManager,
            ApplicationEventPublisher eventPublisher) {
        this(chatClientBuilder, settingsService, null, promptService, jexlTool, planService, chatMemory,
                toolCallingManager, eventPublisher);
    }

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
        return builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build(),
                new ModelCallRetryAdvisor()).build();
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

    @Override
    @SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
    public synchronized void close() {
        Client clientToClose = this.managedClient;
        this.managedClient = null;
        closeClientQuietly(clientToClose);
    }

    @EventListener
    public synchronized void onSettingsChanged(SettingsChangedEvent event) {
        String newApiKey;
        if (event != null && event.settings() != null) {
            newApiKey = event.settings().getGeminiApiKey();
        } else {
            newApiKey = resolveConfiguredApiKey();
        }
        String trimmedKey = StringUtils.trimToEmpty(newApiKey);
        if (!trimmedKey.isEmpty() && !Objects.equals(trimmedKey, this.activeApiKey)) {
            reloadChatClient(trimmedKey);
        }
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
            return StringUtils.defaultIfBlank(extractResponseText(response), "OK");
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
        if (projectService != null) {
            return projectService.getEffectiveGeminiModel();
        }
        return SettingsService.resolveGlobalGeminiModel(settingsService);
    }

    void setRetryTemplate(RetryTemplate retryTemplate) {
        this.retryTemplate = Objects.requireNonNull(retryTemplate, "retryTemplate must not be null");
    }

    public String chat(String conversationId, WorkflowPhase phase, String message, List<byte[]> images) {
        return chat(conversationId, phase, message, null, images);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    public String chat(String conversationId, WorkflowPhase phase, String message, String additionalInstructions,
            List<byte[]> images) {
        ExceptionUtils.checkInterrupted("Chat turn interrupted before start");
        WorkflowPhase resolvedPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        TurnToolContext turnContext = new TurnToolContext(conversationId, resolvedPhase, new AtomicInteger(0));
        String convId = turnContext.conversationId();
        try {
            ChatClient.ChatClientRequestSpec promptSpec = buildPrompt(turnContext, message, additionalInstructions,
                    images);
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            ChatResponse chatResponse = executeChatCall(promptSpec, convId);
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
            String content = formatChatResponse(chatResponse, turnContext);
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

    private ChatResponse executeChatCall(ChatClient.ChatClientRequestSpec promptSpec, String convId) {
        return promptSpec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId)).call().chatResponse();
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
            return RetryUtils.execute(retryTemplate, () -> {
                ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
                return callAdvisorChain.copy(this).nextCall(chatClientRequest);
            });
        }
    }

    String formatChatResponse(ChatResponse chatResponse, TurnToolContext turnContext) {
        String content = extractResponseText(chatResponse);
        String finishReason = extractFinishReason(chatResponse);
        Usage usage = null;
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            usage = chatResponse.getMetadata().getUsage();
        }
        String usageSummary = formatUsageSummary(usage);
        int toolTurns = turnContext.turnCounter().get();
        boolean hasVisibleContent = StringUtils.isNotBlank(content);

        if (isTokenLimitFinishReason(finishReason)) {
            return formatTokenLimitResponse(content, hasVisibleContent, finishReason, toolTurns, usageSummary);
        }
        if (!hasVisibleContent) {
            return formatEmptyResponse(finishReason, toolTurns, usageSummary);
        }
        return content;
    }

    static String extractResponseText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        Generation result = chatResponse.getResult();
        if (result.getOutput() != null) {
            return result.getOutput().getText();
        }
        return null;
    }

    static String extractFinishReason(ChatResponse chatResponse) {
        String rawFinishReason = null;
        if (chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getMetadata() != null) {
            rawFinishReason = chatResponse.getResult().getMetadata().getFinishReason();
        }
        return StringUtils.defaultIfBlank(StringUtils.trim(rawFinishReason), FINISH_REASON_UNKNOWN);
    }

    private static String formatTokenLimitResponse(String content, boolean hasVisibleContent, String finishReason,
            int toolTurns, String usageSummary) {
        if (hasVisibleContent) {
            StringBuilder sb = new StringBuilder(content).append("\n\n---\n> ").append(TRUNCATED_MARKER).append(" ")
                    .append(finishReason)
                    .append(")**: The response was cut off because it reached the maximum output token limit.");
            if (!usageSummary.isEmpty()) {
                sb.append(" (Token usage: ").append(usageSummary).append(")");
            }
            return sb.append(" Reply **'continue'** so Roxy can resume where she left off.").toString();
        }

        StringBuilder sb = new StringBuilder().append(NO_VISIBLE_RESPONSE_MARKER).append(" ").append(finishReason)
                .append(")**\n\n")
                .append("Roxy produced no visible text because the model exhausted its output or thinking token limit before generating a final message.");
        appendDiagnosticMetadata(sb, finishReason, toolTurns, usageSummary);
        return sb.append("\n\nPlease ask Roxy to **'continue'** or summarize in smaller steps.").toString();
    }

    private static String formatEmptyResponse(String finishReason, int toolTurns, String usageSummary) {
        boolean normalStop = "STOP".equalsIgnoreCase(finishReason)
                || FINISH_REASON_UNKNOWN.equalsIgnoreCase(finishReason);

        StringBuilder sb = new StringBuilder(EMPTY_RESPONSE_MARKER).append(" ").append(finishReason).append(")**\n\n");
        if (normalStop) {
            sb.append(EMPTY_STOP_MESSAGE);
        } else {
            sb.append("Generation was blocked or aborted by the model (").append(finishReason).append(").");
        }
        appendDiagnosticMetadata(sb, finishReason, toolTurns, usageSummary);
        if (normalStop) {
            sb.append(
                    "\n\nPlease reply (e.g., *'Please continue and summarize your findings'*) so Roxy can provide the final response.");
        } else {
            sb.append(
                    "\n\nPlease review the last step or ask Roxy to continue with a rephrased request or simpler tool call.");
        }
        return sb.toString();
    }

    private static void appendDiagnosticMetadata(StringBuilder sb, String finishReason, int toolTurns,
            String usageSummary) {
        sb.append("\n- **Finish reason:** ").append(finishReason).append("\n- **Tool turns executed:** ")
                .append(toolTurns);
        if (!usageSummary.isEmpty()) {
            sb.append("\n- **Token usage:** ").append(usageSummary);
        }
    }

    private static boolean isTokenLimitFinishReason(String finishReason) {
        return "MAX_TOKENS".equalsIgnoreCase(finishReason) || "LENGTH".equalsIgnoreCase(finishReason);
    }

    private static String formatUsageSummary(Usage usage) {
        if (usage == null) {
            return "";
        }
        int promptTokens = 0;
        if (usage.getPromptTokens() != null) {
            promptTokens = usage.getPromptTokens();
        }
        int completionTokens = 0;
        if (usage.getCompletionTokens() != null) {
            completionTokens = usage.getCompletionTokens();
        }
        int totalTokens = 0;
        if (usage.getTotalTokens() != null) {
            totalTokens = usage.getTotalTokens();
        }
        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            return "";
        }
        return String.format("promptTokens=%d, completionTokens=%d, totalTokens=%d", promptTokens, completionTokens,
                totalTokens);
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(TurnToolContext turnContext, String message,
            String additionalInstructions, List<byte[]> images) {
        WorkflowPhase currentPhase = turnContext.phase();
        ChatClient.ChatClientRequestSpec promptSpec = chatClient.prompt()
                .system(promptService.buildSystemPrompt(currentPhase, additionalInstructions)).user(u -> {
                    u.text(message);
                    if (images != null) {
                        for (byte[] img : images) {
                            u.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(img));
                        }
                    }
                }).options(GoogleGenAiChatOptions.builder().model(resolveEffectiveGeminiModel()));
        promptSpec.tools(jexlTool);
        promptSpec.advisors(new AutoNudgeAdvisor(turnContext, chatMemory));
        return promptSpec.toolContext(turnContext.toMap());
    }

    private void publishTurnComplete(String conversationId, int totalTurns, String result) {
        eventPublisher.publishEvent(new AgentTurnCompleteEvent(conversationId, AGENT_NAME, totalTurns, result));
    }
}
