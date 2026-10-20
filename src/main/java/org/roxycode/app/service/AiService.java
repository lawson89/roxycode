package org.roxycode.app.service;

import java.util.List;
import java.util.Map;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
    static final String TOOL_CONTEXT_JEXL_TOOL = "jexlTool";

    public String chat(String message) {
        return chat("default", planService.getCurrentPhase(), message, null, null);
    }

    public String chat(String message, List<byte[]> images) {
        return chat("default", planService.getCurrentPhase(), message, null, images);
    }

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final PromptService promptService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;

    public AiService(
            ChatClient.Builder chatClientBuilder,
            SettingsService settingsService,
            PromptService promptService,
            JexlServiceRegistry jexlServiceRegistry,
            PlanService planService,
            ChatMemory chatMemory,
            ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.planService = planService;
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
                if (!result.returnDirect() && isDirectReturn(prompt)) {
                    return ToolExecutionResult.builder()
                            .conversationHistory(result.conversationHistory())
                            .returnDirect(true)
                            .build();
                }
                return result;
            }
        };
    }

    private static boolean isDirectReturn(Prompt prompt) {
        if (prompt != null
                && prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null
                && options.getToolContext().get(TOOL_CONTEXT_JEXL_TOOL)
                        instanceof JexlTool jexlTool) {
            return jexlTool.isReturnDirect();
        }
        return false;
    }

    public String chat(
            String conversationId,
            WorkflowPhase phase,
            String message,
            String systemPromptText,
            List<byte[]> images) {
        ExceptionUtils.checkInterrupted("Chat turn interrupted before start");
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
            ChatClient.ChatClientRequestSpec promptSpec =
                    buildPrompt(phase, message, systemPromptText, images, ephemeralJexlTool);
            ExceptionUtils.checkInterrupted("Chat turn interrupted before model call");
            content =
                    promptSpec
                            .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                            .call()
                            .content();
            ExceptionUtils.checkInterrupted("Chat turn interrupted after model call");
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "Chat turn cancelled");
            publishError(conversationId, ephemeralJexlTool.getTurnCount(), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Unexpected error during chat turn", e);
        }

        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(
                        conversationId, "Roxy", ephemeralJexlTool.getTurnCount(), content));
        return content;
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(
            WorkflowPhase phase,
            String message,
            String systemPromptText,
            List<byte[]> images,
            JexlTool tools) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        WorkflowPhase currentPhase = (phase != null) ? phase : planService.getCurrentPhase();
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
        if (tools != null) {
            promptSpec.tools(tools);
            promptSpec.toolContext(Map.of(TOOL_CONTEXT_JEXL_TOOL, tools));
        }
        return promptSpec;
    }

    private void publishError(String conversationId, int turnCount, Exception e) {
        eventPublisher.publishEvent(
                new AgentTurnCompleteEvent(
                        conversationId, "Roxy", turnCount, "Error: " + e.getMessage()));
    }
}
