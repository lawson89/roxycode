package org.roxycode.app.service;

import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for interacting with the AI Chat Model using the modern ChatClient fluent API.
 */
@Service
public class AiService {
    public String chat(String message) {
        return chat(message, null);
    }

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final PromptService promptService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final WorkflowService workflowService;
    private final JexlTool jexlTool;
    private final ChatMemory chatMemory;
    private final ApplicationEventPublisher eventPublisher;

    public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService, 
                     PromptService promptService,
                     JexlServiceRegistry jexlServiceRegistry, JexlTool jexlTool,
                     WorkflowService workflowService, ChatMemory chatMemory,
                     ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.workflowService = workflowService;
        this.jexlTool = jexlTool;
        this.chatMemory = chatMemory;
        this.eventPublisher = eventPublisher;
        this.chatClient = chatClientBuilder
                .defaultTools(jexlTool)
                .build();
    }

    public String chat(String message, String systemPromptText) {
        AtomicInteger turnCount = new AtomicInteger(0);
        int maxTurns = settingsService.getSettings().getMaxAgentToolTurns();
        JexlExecutionListener turnListener = event -> {
            int turn = turnCount.incrementAndGet();
            eventPublisher.publishEvent(new AgentTurnEvent("Roxy", turn, workflowService.getCurrentPhase().name()));
            if (turn > maxTurns) {
                throw new RuntimeException("MAX_TOOL_TURNS_EXCEEDED");
            }
        };
        
        jexlTool.addListener(turnListener);
        try {
            String conversationId = "default";
            String content;
            try {
                content = buildPrompt(message, systemPromptText)
                        .call()
                        .content();
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("MAX_TOOL_TURNS_EXCEEDED")) {
                    content = "Autonomous execution stopped: Maximum tool turns (" + maxTurns + ") exceeded.";
                } else {
                    throw e;
                }
            }
            
            // Save to memory manually
            chatMemory.add(conversationId, List.of(new UserMessage(message)));
            chatMemory.add(conversationId, List.of(new AssistantMessage(content)));
            
            eventPublisher.publishEvent(new AgentTurnCompleteEvent("Roxy", turnCount.get(), content));
            return content;
        } finally {
            jexlTool.removeListener(turnListener);
        }
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(String message, String systemPromptText) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        String jexlDocs = jexlServiceRegistry.getDocumentation();
        String jexlContext = promptService.loadJexlContext();
        
        WorkflowPhase currentPhase = workflowService.getCurrentPhase();
        AgentRole currentRole = currentPhase.getRole();
        
        StringBuilder systemPrompt = new StringBuilder(promptService.loadCoreWorkflowPrompt());
        systemPrompt.append("\n\n## DYNAMIC CONTEXT\n");
        systemPrompt.append("CURRENT PHASE: ").append(currentPhase.name()).append(" (").append(currentPhase.getDisplayName()).append(")\n");
        systemPrompt.append("CURRENT ROLE: ").append(currentRole.getTitle()).append("\n");
        systemPrompt.append(currentRole.getSystemPromptPrefix()).append("\n\n");
        
        if (systemPromptText != null) {
            systemPrompt.append(systemPromptText).append("\n\n");
        }
        systemPrompt.append("\n\n## JEXL CONTEXT\n").append(jexlContext).append("\n\n");
        systemPrompt.append("You have access to the following JEXL tools:\n").append(jexlDocs);
        
        // Retrieve history from memory
        List<Message> history = chatMemory.get("default");
        
        return chatClient.prompt()
                .system(systemPrompt.toString())
                .messages(history)
                .user(message)
                .options(GoogleGenAiChatOptions.builder().model(activeModel));
    }
}