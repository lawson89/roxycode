package org.roxycode.app.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
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
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String AGENT_NAME = "Roxy";
    private static final String FINISH_REASON_UNKNOWN = "UNKNOWN";

    public static final String DEFAULT_TOPIC_TITLE = "Codebase Q&A Topic";
    static final int MAX_TOPIC_TITLE_LENGTH = 80;
    private static final int MAX_EXCERPT_LENGTH = 4000;
    static final String TOPIC_TITLE_SYSTEM_PROMPT = "Generate a concise, descriptive 3 to 6 word title summarizing this technical Q&A topic. "
            + "Return ONLY the plain title text without quotes, markdown, or punctuation.";

    public static final String TRUNCATED_MARKER = "⚠️ **Response Truncated (finishReason:";
    public static final String NO_VISIBLE_RESPONSE_MARKER = "⚠️ **No Visible Response (finishReason:";
    public static final String EMPTY_RESPONSE_MARKER = "⚠️ **Empty Response Returned (finishReason:";
    public static final String EMPTY_STOP_MESSAGE = "Roxy completed the turn without generating a visible text message.";
    private static final String STRIP_TITLE_CHARS_REGEX = "[#*_~`\"']";

    public static final Duration DEFAULT_TOPIC_TITLE_TIMEOUT = Duration.ofSeconds(10);
    private static final ExecutorService TITLE_TIMEOUT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatClient statelessChatClient;
    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final PromptService promptService;
    private final JexlTool jexlTool;
    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;
    private volatile RetryTemplate retryTemplate = AiConfig.createRetryTemplate();
    private volatile Duration topicTitleTimeout = DEFAULT_TOPIC_TITLE_TIMEOUT;

    public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService, PromptService promptService,
            JexlTool jexlTool, PlanService planService, ChatMemory chatMemory, ToolCallingManager toolCallingManager,
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlTool = jexlTool;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.statelessChatClient = chatClientBuilder.build();
        this.chatClient = chatClientBuilder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build(),
                new ModelCallRetryAdvisor()).build();
    }

    void setRetryTemplate(RetryTemplate retryTemplate) {
        this.retryTemplate = Objects.requireNonNull(retryTemplate, "retryTemplate must not be null");
    }

    RetryTemplate getRetryTemplate() {
        return retryTemplate;
    }

    void setTopicTitleTimeout(Duration topicTitleTimeout) {
        this.topicTitleTimeout = Objects.requireNonNull(topicTitleTimeout, "topicTitleTimeout must not be null");
    }

    Duration getTopicTitleTimeout() {
        return topicTitleTimeout;
    }

    public String generateTopicTitle(String conversationExcerpt) {
        if (StringUtils.isBlank(conversationExcerpt))
            return DEFAULT_TOPIC_TITLE;
        String excerpt = conversationExcerpt.length() > MAX_EXCERPT_LENGTH
                ? conversationExcerpt.substring(0, MAX_EXCERPT_LENGTH)
                : conversationExcerpt;
        Future<ChatResponse> future = TITLE_TIMEOUT_EXECUTOR
                .submit(() -> statelessChatClient.prompt().system(TOPIC_TITLE_SYSTEM_PROMPT).user(u -> u.text(excerpt))
                        .options(GoogleGenAiChatOptions.builder().model(settingsService.getSettings().getGeminiModel()))
                        .call().chatResponse());
        try {
            ChatResponse response = future.get(Math.max(1L, topicTitleTimeout.toMillis()), TimeUnit.MILLISECONDS);
            String rawTitle = extractResponseText(response);
            String sanitized = sanitizeTopicTitle(rawTitle);
            if (!sanitized.isEmpty())
                return sanitized;
        } catch (Exception e) {
            future.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to generate topic title via LLM, using fallback: {}", e.getMessage());
        }
        return deriveFallbackTopicTitle(conversationExcerpt);
    }

    static String sanitizeTopicTitle(String rawTitle) {
        if (StringUtils.isBlank(rawTitle))
            return "";
        String firstLine = rawTitle.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
        if (firstLine.isEmpty())
            return "";
        String cleaned = firstLine.replaceAll(STRIP_TITLE_CHARS_REGEX, " ").replaceAll("\\s+", " ")
                .replaceAll("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$", "").trim();
        return cleaned.length() > MAX_TOPIC_TITLE_LENGTH
                ? cleaned.substring(0, MAX_TOPIC_TITLE_LENGTH).replaceAll("[^a-zA-Z0-9]+$", "").trim()
                : cleaned;
    }

    public static String deriveFallbackTopicTitle(String conversationExcerpt) {
        if (StringUtils.isBlank(conversationExcerpt))
            return DEFAULT_TOPIC_TITLE;
        for (String rawLine : conversationExcerpt.lines().toList()) {
            String line = rawLine.trim();
            if (line.isEmpty())
                continue;
            if (line.regionMatches(true, 0, "User:", 0, 5)) {
                line = line.substring(5).trim();
            } else if (line.regionMatches(true, 0, "Roxy:", 0, 5) || line.regionMatches(true, 0, "AI:", 0, 3)) {
                continue;
            }
            String sanitized = sanitizeTopicTitle(line);
            if (!sanitized.isEmpty())
                return sanitized;
        }
        String fallback = sanitizeTopicTitle(conversationExcerpt);
        return fallback.isEmpty() ? DEFAULT_TOPIC_TITLE : fallback;
    }

    public String chat(String conversationId, WorkflowPhase phase, String message, List<byte[]> images) {
        return chat(conversationId, phase, message, null, images);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    public String chat(String conversationId, WorkflowPhase phase, String message, String additionalInstructions,
            List<byte[]> images) {
        ExceptionUtils.checkInterrupted("Chat turn interrupted before start");
        TurnToolContext turnContext = new TurnToolContext(conversationId,
                phase != null ? phase : planService.getCurrentPhase(), new AtomicInteger(0));
        String convId = turnContext.conversationId();

        AiConfig.clearHandledRetryState();
        try {
            ChatClient.ChatClientRequestSpec promptSpec = buildPrompt(turnContext, message, additionalInstructions,
                    images);
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            ChatResponse chatResponse = executeChatCall(promptSpec, convId, turnContext);
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
            String content = formatChatResponse(chatResponse, turnContext);
            publishTurnComplete(convId, turnContext.turnCounter().get(), content);
            return content;
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Chat turn cancelled");
            if (e instanceof RuntimeException re)
                throw re;
            throw new RuntimeException("Unexpected error during chat turn", e);
        } finally {
            AiConfig.clearHandledRetryState();
        }
    }

    private ChatResponse executeChatCall(ChatClient.ChatClientRequestSpec promptSpec, String convId,
            TurnToolContext turnContext) {
        return RetryUtils.execute(retryTemplate, () -> {
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            try {
                return promptSpec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId)).call().chatResponse();
            } catch (Throwable t) {
                if (turnContext.turnCounter().get() > 0) {
                    AiConfig.markHandledTerminalException(t);
                }
                throw t;
            }
        });
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
        Usage usage = (chatResponse != null && chatResponse.getMetadata() != null)
                ? chatResponse.getMetadata().getUsage()
                : null;
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

    private static String extractResponseText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        Generation result = chatResponse.getResult();
        return result.getOutput() != null ? result.getOutput().getText() : null;
    }

    private static String extractFinishReason(ChatResponse chatResponse) {
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
        sb.append(normalStop
                ? "\n\nPlease reply (e.g., *'Please continue and summarize your findings'*) so Roxy can provide the final response."
                : "\n\nPlease review the last step or ask Roxy to continue with a rephrased request or simpler tool call.");
        return sb.toString();
    }

    private static void appendDiagnosticMetadata(StringBuilder sb, String finishReason, int toolTurns,
            String usageSummary) {
        sb.append("\n- **Finish reason:** ").append(finishReason).append("\n- **Tool turns executed:** ")
                .append(toolTurns);
        if (!usageSummary.isEmpty())
            sb.append("\n- **Token usage:** ").append(usageSummary);
    }

    public static boolean isAutoNudgeableResponse(String response, boolean hadToolTurns) {
        if (StringUtils.isBlank(response))
            return false;
        if (response.startsWith(JexlTool.MAX_TURNS_EXCEEDED_PREFIX) || response.contains(TRUNCATED_MARKER)
                || response.contains(NO_VISIBLE_RESPONSE_MARKER)) {
            return true;
        }
        return hadToolTurns && response.contains(EMPTY_RESPONSE_MARKER) && response.contains(EMPTY_STOP_MESSAGE);
    }

    private static boolean isTokenLimitFinishReason(String finishReason) {
        return "MAX_TOKENS".equalsIgnoreCase(finishReason) || "LENGTH".equalsIgnoreCase(finishReason);
    }

    private static String formatUsageSummary(Usage usage) {
        if (usage == null) {
            return "";
        }
        int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
        return (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0)
                ? ""
                : String.format("promptTokens=%d, completionTokens=%d, totalTokens=%d", promptTokens, completionTokens,
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
                }).options(GoogleGenAiChatOptions.builder().model(settingsService.getSettings().getGeminiModel()));
        promptSpec.tools(jexlTool);
        return promptSpec.toolContext(turnContext.toMap());
    }

    private void publishTurnComplete(String conversationId, int totalTurns, String result) {
        eventPublisher.publishEvent(new AgentTurnCompleteEvent(conversationId, AGENT_NAME, totalTurns, result));
    }
}
