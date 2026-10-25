package org.roxycode.app.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private static final String AGENT_NAME = "Roxy";
    private static final String FINISH_REASON_UNKNOWN = "UNKNOWN";

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final PromptService promptService;
    private final JexlTool jexlTool;
    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;

    public AiService(
            ChatClient.Builder chatClientBuilder,
            SettingsService settingsService,
            PromptService promptService,
            JexlTool jexlTool,
            PlanService planService,
            ChatMemory chatMemory,
            ToolCallingManager toolCallingManager,
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlTool = jexlTool;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.chatClient =
                chatClientBuilder
                        .defaultAdvisors(
                                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                ToolCallingAdvisor.builder()
                                        .toolCallingManager(toolCallingManager)
                                        .build())
                        .build();
    }

    public String chat(
            String conversationId, WorkflowPhase phase, String message, List<byte[]> images) {
        return chat(conversationId, phase, message, null, images);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    public String chat(
            String conversationId,
            WorkflowPhase phase,
            String message,
            String additionalInstructions,
            List<byte[]> images) {
        ExceptionUtils.checkInterrupted("Chat turn interrupted before start");
        TurnToolContext turnContext =
                new TurnToolContext(
                        conversationId,
                        phase != null ? phase : planService.getCurrentPhase(),
                        new AtomicInteger(0));
        String convId = turnContext.conversationId();
        AtomicInteger turnCounter = turnContext.turnCounter();

        try {
            ChatClient.ChatClientRequestSpec promptSpec =
                    buildPrompt(turnContext, message, additionalInstructions, images);
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            ChatResponse chatResponse =
                    promptSpec
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                            .call()
                            .chatResponse();
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
            String content = formatChatResponse(chatResponse, turnCounter.get());
            publishTurnComplete(convId, turnCounter.get(), content);
            return content;
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Chat turn cancelled");
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected error during chat turn", e);
        }
    }

    String formatChatResponse(ChatResponse chatResponse, int toolTurns) {
        String content = null;
        String rawFinishReason = null;
        Usage usage = null;

        if (chatResponse != null) {
            Generation result = chatResponse.getResult();
            if (result != null) {
                if (result.getOutput() != null) {
                    content = result.getOutput().getText();
                }
                if (result.getMetadata() != null) {
                    rawFinishReason = result.getMetadata().getFinishReason();
                }
            }
            if (chatResponse.getMetadata() != null) {
                usage = chatResponse.getMetadata().getUsage();
            }
        }

        String finishReason =
                StringUtils.defaultIfBlank(
                        StringUtils.trim(rawFinishReason), FINISH_REASON_UNKNOWN);
        String usageSummary = formatUsageSummary(usage);
        boolean hasVisibleContent = StringUtils.isNotBlank(content);

        if (isTokenLimitFinishReason(finishReason)) {
            if (hasVisibleContent) {
                StringBuilder sb = new StringBuilder(content);
                sb.append("\n\n---\n")
                        .append("> ⚠️ **Response Truncated (finishReason: ")
                        .append(finishReason)
                        .append(
                                ")**: The response was cut off because it reached the maximum output token limit.");
                if (!usageSummary.isEmpty()) {
                    sb.append(" (Token usage: ").append(usageSummary).append(")");
                }
                sb.append(" Reply **'continue'** so Roxy can resume where she left off.");
                return sb.toString();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("⚠️ **No Visible Response (finishReason: ")
                    .append(finishReason)
                    .append(")**\n\n")
                    .append(
                            "Roxy produced no visible text because the model exhausted its output or thinking token limit before generating a final message.");
            sb.append("\n- **Finish reason:** ").append(finishReason);
            sb.append("\n- **Tool turns executed:** ").append(toolTurns);
            if (!usageSummary.isEmpty()) {
                sb.append("\n- **Token usage:** ").append(usageSummary);
            }
            sb.append("\n\nPlease ask Roxy to **'continue'** or summarize in smaller steps.");
            return sb.toString();
        }

        if (!hasVisibleContent) {
            boolean normalStop =
                    "STOP".equalsIgnoreCase(finishReason)
                            || FINISH_REASON_UNKNOWN.equalsIgnoreCase(finishReason);
            StringBuilder sb = new StringBuilder();
            sb.append("⚠️ **Empty Response Returned (finishReason: ")
                    .append(finishReason)
                    .append(")**\n\n");
            if (normalStop) {
                sb.append("Roxy completed the turn without generating a visible text message.");
            } else {
                sb.append("Generation was blocked or aborted by the model (")
                        .append(finishReason)
                        .append(").");
            }
            sb.append("\n- **Finish reason:** ").append(finishReason);
            sb.append("\n- **Tool turns executed:** ").append(toolTurns);
            if (!usageSummary.isEmpty()) {
                sb.append("\n- **Token usage:** ").append(usageSummary);
            }
            if (normalStop) {
                sb.append(
                        "\n\nPlease reply (e.g., *'Please continue and summarize your findings'*) so Roxy can provide the final response.");
            } else {
                sb.append(
                        "\n\nPlease review the last step or ask Roxy to continue with a rephrased request or simpler tool call.");
            }
            return sb.toString();
        }

        return content;
    }

    private static boolean isTokenLimitFinishReason(String finishReason) {
        return "MAX_TOKENS".equalsIgnoreCase(finishReason)
                || "LENGTH".equalsIgnoreCase(finishReason);
    }

    private static String formatUsageSummary(Usage usage) {
        if (usage == null) {
            return "";
        }
        int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens =
                usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            return "";
        }
        return String.format(
                "promptTokens=%d, completionTokens=%d, totalTokens=%d",
                promptTokens, completionTokens, totalTokens);
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(
            TurnToolContext turnContext,
            String message,
            String additionalInstructions,
            List<byte[]> images) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        WorkflowPhase currentPhase = turnContext.phase();
        String systemPrompt = promptService.buildSystemPrompt(currentPhase, additionalInstructions);

        ChatClient.ChatClientRequestSpec promptSpec =
                chatClient
                        .prompt()
                        .system(systemPrompt)
                        .user(
                                u -> {
                                    u.text(message);
                                    if (images != null) {
                                        for (byte[] img : images) {
                                            u.media(
                                                    org.springframework.util.MimeTypeUtils
                                                            .IMAGE_PNG,
                                                    new org.springframework.core.io
                                                            .ByteArrayResource(img));
                                        }
                                    }
                                })
                        .options(
                                org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                                        .model(activeModel));
        if (currentPhase == WorkflowPhase.EXPLORE) {
            promptSpec.tools(jexlTool);
        } else {
            promptSpec.tools(jexlTool, planService);
        }
        promptSpec.toolContext(turnContext.toMap());
        return promptSpec;
    }

    private void publishTurnComplete(String conversationId, int totalTurns, String result) {
        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(conversationId, AGENT_NAME, totalTurns, result));
    }
}
