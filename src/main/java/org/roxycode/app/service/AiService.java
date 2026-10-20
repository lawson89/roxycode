package org.roxycode.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiService.class);
        private static final String NEWLINE_DOUBLE = "\n\n";
    private static final String FIELD_MESSAGE = "message";
    public String chat(String message) {
        return chat("default", workflowService.getCurrentPhase(), message, null, null);
    }

    public String chat(String message, List<byte[]> images) {
        return chat("default", workflowService.getCurrentPhase(), message, null, images);
    }

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final PromptService promptService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final WorkflowService workflowService;
    private final ChatMemory chatMemory;
    private final ApplicationEventPublisher eventPublisher;

    public AiService(
            ChatClient.Builder chatClientBuilder,
            SettingsService settingsService,
            PromptService promptService,
            JexlServiceRegistry jexlServiceRegistry,
            WorkflowService workflowService,
            ChatMemory chatMemory,
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.workflowService = workflowService;
        this.chatMemory = chatMemory;
        this.eventPublisher = eventPublisher;
        ToolCallingManager defaultToolCallingManager =
                ToolCallingManager.builder()
                        .toolExecutionExceptionProcessor(
                                DefaultToolExecutionExceptionProcessor.builder()
                                        .alwaysThrow(true)
                                        .build())
                        .build();
        ToolCallingManager returnDirectToolCallingManager =
                createReturnDirectToolCallingManager(defaultToolCallingManager);
        this.chatClient =
                chatClientBuilder
                        .defaultAdvisors(
                                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                ToolCallingAdvisor.builder()
                                        .toolCallingManager(returnDirectToolCallingManager)
                                        .build())
                        .build();
    }

    final ToolCallingManager createReturnDirectToolCallingManager(ToolCallingManager delegate) {
        return new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return delegate.resolveToolDefinitions(chatOptions);
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
                if (isDirectReturnResult(result)) {
                    return ToolExecutionResult.builder()
                            .conversationHistory(result.conversationHistory())
                            .returnDirect(true)
                            .build();
                }
                return result;
            }
        };
    }

    private static boolean isDirectReturnResult(ToolExecutionResult result) {
        if (result == null
                || result.conversationHistory() == null
                || result.conversationHistory().isEmpty()) {
            return false;
        }
        Message lastMessage =
                result.conversationHistory().get(result.conversationHistory().size() - 1);
        if (lastMessage instanceof ToolResponseMessage toolMsg) {
            for (ToolResponseMessage.ToolResponse resp : toolMsg.getResponses()) {
                String data = resp.responseData();
                if (WorkflowService.isTransitionResponse(data)
                        || JexlTool.isMaxTurnsExceededResponse(data)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String chat(
            String conversationId,
            WorkflowPhase phase,
            String message,
            String systemPromptText,
            List<byte[]> images) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Chat turn interrupted before start");
        }
        int maxTurns = settingsService.getSettings().getMaxAgentToolTurns();
        JexlTool ephemeralJexlTool = new JexlTool(jexlServiceRegistry, phase, maxTurns);

        ephemeralJexlTool.addListener(
                event -> {
                    int turn = ephemeralJexlTool.getTurnCount();
                    eventPublisher.publishEvent(
                            new AgentTurnEvent(conversationId, "Roxy", turn, phase.name()));
                    eventPublisher.publishEvent(
                            new ToolExecutionEvent(
                                    conversationId,
                                    event.script(),
                                    event.result(),
                                    event.success(),
                                    event.error()));
                });

        String content;
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Chat turn interrupted before prompt build");
            }
            ChatClient.ChatClientRequestSpec promptSpec =
                    buildPrompt(phase, message, systemPromptText, images, ephemeralJexlTool);
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Chat turn interrupted after prompt build");
            }
            content =
                    promptSpec
                            .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                            .call()
                            .content();
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Chat turn interrupted after model call");
            }

            content = formatStructuredContent(content);
        } catch (CancellationException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException e) {
            if (ExceptionUtils.findCause(e, InterruptedException.class) != null
                    || ExceptionUtils.findCause(e, ClosedByInterruptException.class) != null
                    || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw e;
            }
            publishError(conversationId, ephemeralJexlTool.getTurnCount(), e);
            throw e;
        } catch (Exception e) {
            CancellationException cancelCause =
                    ExceptionUtils.findCause(e, CancellationException.class);
            if (cancelCause != null
                    || ExceptionUtils.findCause(e, InterruptedException.class) != null
                    || ExceptionUtils.findCause(e, ClosedByInterruptException.class) != null
                    || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw (CancellationException)
                        new CancellationException(
                                        cancelCause != null
                                                ? cancelCause.getMessage()
                                                : "Chat turn cancelled")
                                .initCause(e);
            }
            publishError(conversationId, ephemeralJexlTool.getTurnCount(), e);
            throw new RuntimeException("Unexpected error during chat turn", e);
        }

        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(
                        conversationId, "Roxy", ephemeralJexlTool.getTurnCount(), content));
        return content;
    }

    private String formatStructuredContent(String raw) {
        if (raw != null && raw.contains(FIELD_MESSAGE)) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '"')) {
                try {
                    JsonNode node = AiConfig.SHARED_OBJECT_MAPPER.readTree(trimmed);
                    if (node.isTextual()) {
                        node = AiConfig.SHARED_OBJECT_MAPPER.readTree(node.asText());
                    }
                    if (node.has("summary")
                            && !node.get("summary").asText().isBlank()
                            && node.has(FIELD_MESSAGE)) {
                        return node.get("summary").asText()
                                + NEWLINE_DOUBLE
                                + node.get(FIELD_MESSAGE).asText();
                    } else if (node.has(FIELD_MESSAGE)) {
                        return node.get(FIELD_MESSAGE).asText();
                    }
                } catch (Exception e) {
                    log.trace("Response is not valid JSON, using raw content");
                }
            }
        }
        return raw;
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(
            WorkflowPhase phase,
            String message,
            String systemPromptText,
            List<byte[]> images,
            JexlTool tools) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        WorkflowPhase currentPhase = (phase != null) ? phase : workflowService.getCurrentPhase();
        String systemPrompt = promptService.buildSystemPrompt(currentPhase, systemPromptText);

        return chatClient
                .prompt()
                .tools(tools)
                .system(systemPrompt)
                .user(
                        u -> {
                            u.text(message);
                            if (images != null) {
                                for (byte[] img : images) {
                                    u.media(
                                            org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                                            new org.springframework.core.io.ByteArrayResource(img));
                                }
                            }
                        })
                .options(
                        org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                                .model(activeModel));
    }

    private void publishError(String conversationId, int turnCount, Exception e) {
        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(
                        conversationId, "Roxy", turnCount, "Error: " + e.getMessage()));
    }
}
