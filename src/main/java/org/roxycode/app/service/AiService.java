package org.roxycode.app.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.ReturnDirectToolCallingManager;
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
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlTool = jexlTool;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        ToolCallingManager returnDirectToolCallingManager =
                new ReturnDirectToolCallingManager(settingsService, planService, eventPublisher);
        this.chatClient =
                chatClientBuilder
                        .defaultAdvisors(
                                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                ToolCallingAdvisor.builder()
                                        .toolCallingManager(returnDirectToolCallingManager)
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
            String conversationId,
            WorkflowPhase phase,
            String message,
            String systemPromptText,
            List<byte[]> images) {
        ExceptionUtils.checkInterrupted("Chat turn interrupted before start");
        TurnToolContext turnContext =
                new TurnToolContext(
                        conversationId,
                        phase != null ? phase : planService.getCurrentPhase(),
                        new AtomicInteger(0));
        String convId = turnContext.conversationId();
        AtomicInteger turnCounter = turnContext.turnCounter();

        String content;
        try {
            ChatClient.ChatClientRequestSpec promptSpec =
                    buildPrompt(turnContext, message, systemPromptText, images);
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            content =
                    promptSpec
                            .advisors(a -> a.param("chat_memory_conversation_id", convId))
                            .call()
                            .content();
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Chat turn cancelled");
            publishError(convId, turnCounter.get(), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected error during chat turn", e);
        }

        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(convId, AGENT_NAME, turnCounter.get(), content));
        return content;
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(
            TurnToolContext turnContext,
            String message,
            String systemPromptText,
            List<byte[]> images) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        WorkflowPhase currentPhase = turnContext.phase();
        String systemPrompt = promptService.buildSystemPrompt(currentPhase, systemPromptText);

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

    private void publishError(String conversationId, int turnCount, Exception e) {
        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(
                        conversationId, AGENT_NAME, turnCount, "Error: " + e.getMessage()));
    }
}
