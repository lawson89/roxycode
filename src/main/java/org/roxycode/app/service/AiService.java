package org.roxycode.app.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private static final String AGENT_NAME = "Roxy";

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

    public String chat(String message) {
        return chat(
                TurnToolContext.DEFAULT_CONVERSATION_ID,
                planService.getCurrentPhase(),
                message,
                null,
                null);
    }

    public String chat(String message, List<byte[]> images) {
        return chat(
                TurnToolContext.DEFAULT_CONVERSATION_ID,
                planService.getCurrentPhase(),
                message,
                null,
                images);
    }

    public String chat(
            String conversationId, WorkflowPhase phase, String message, List<byte[]> images) {
        return chat(conversationId, phase, message, null, images);
    }

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
            String content =
                    promptSpec
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                            .call()
                            .content();
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
            publishTurnComplete(convId, turnCounter.get(), content);
            return content;
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Chat turn cancelled");
            publishError(convId, turnCounter.get(), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected error during chat turn", e);
        }
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

    private void publishError(String conversationId, int turnCount, Exception e) {
        publishTurnComplete(conversationId, turnCount, "Error: " + e.getMessage());
    }
}
