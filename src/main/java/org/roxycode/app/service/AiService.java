package org.roxycode.app.service;

import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.ai.services.ExploreManager;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiService {
    public String chat(String message) { return chat(message, null, null); }
    public String chat(String message, List<byte[]> images) { return chat(message, null, images); }

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final PromptService promptService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final WorkflowService workflowService;
    private final JexlTool jexlTool;
    private final ChatMemory chatMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final RepoMapPackerService repoMapPackerService;
    private final GitService gitService;
    private final ProjectAnalysisService projectAnalysisService;
    private final ExploreManager exploreManager;

        public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService, 
                     PromptService promptService,
                     JexlServiceRegistry jexlServiceRegistry, JexlTool jexlTool,
                     WorkflowService workflowService, ChatMemory chatMemory,
                     ApplicationEventPublisher eventPublisher,
                     RepoMapPackerService repoMapPackerService,
                     GitService gitService,
                     ProjectAnalysisService projectAnalysisService,
                     ExploreManager exploreManager) {
        this.settingsService = settingsService;
        this.promptService = promptService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.workflowService = workflowService;
        this.jexlTool = jexlTool;
        this.chatMemory = chatMemory;
        this.eventPublisher = eventPublisher;
        this.repoMapPackerService = repoMapPackerService;
        this.gitService = gitService;
        this.projectAnalysisService = projectAnalysisService;
        this.exploreManager = exploreManager;
        this.chatClient = chatClientBuilder
                .defaultTools(jexlTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String chat(String message, String systemPromptText, List<byte[]> images) {
        AtomicInteger turnCount = new AtomicInteger(0);
        int maxTurns = settingsService.getSettings().getMaxAgentToolTurns();
        JexlExecutionListener turnListener = event -> {
            int turn = turnCount.incrementAndGet();
            eventPublisher.publishEvent(new AgentTurnEvent("Roxy", turn, workflowService.getCurrentPhase().name()));
            if (turn > maxTurns) throw new RuntimeException("MAX_TOOL_TURNS_EXCEEDED");
        };
        
        jexlTool.addListener(turnListener);
        try {
            String conversationId = "default";
            String content;
            try {
                                content = buildPrompt(message, systemPromptText, images)
                        .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                        .call()
                        .content();
                
                // If the LLM parroted a yield JSON, format it for the user
                if (content != null && content.trim().startsWith("{") && content.contains("\"message\"")) {
                    try {
                        JsonNode node = new ObjectMapper().readTree(content);
                        if (node.has("summary") && node.has("message")) {
                            content = node.get("summary").asText() + "\n\n" + node.get("message").asText();
                        } else if (node.has("message")) {
                            content = node.get("message").asText();
                        }
                    } catch (Exception e) {
                        // Not valid JSON or different structure, keep as is
                    }
                }
                        } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("MAX_TOOL_TURNS_EXCEEDED")) {
                    content = "Autonomous execution stopped: Maximum tool turns (" + maxTurns + ") exceeded.";
                } else {
                    eventPublisher.publishEvent(new AgentTurnCompleteEvent("Roxy", turnCount.get(), "Error: " + e.getMessage()));
                    throw e;
                }
            }
            
            eventPublisher.publishEvent(new AgentTurnCompleteEvent("Roxy", turnCount.get(), content));
            return content;
        } finally {
            jexlTool.removeListener(turnListener);
        }
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(String message, String systemPromptText, List<byte[]> images) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        WorkflowPhase currentPhase = workflowService.getCurrentPhase();
        AgentRole currentRole = currentPhase.getRole();
        
        StringBuilder systemPrompt = new StringBuilder(promptService.loadCoreWorkflowPrompt());
                systemPrompt.append(promptService.loadAllPrompts());
        systemPrompt.append("\n\n## JEXL CONTEXT\n").append(promptService.loadJexlContext()).append("\n\n");
        systemPrompt.append(promptService.loadAllDocs());
        systemPrompt.append(promptService.loadProjectContext());

        // Phase-specific prompt injection
        if (currentPhase == WorkflowPhase.EXPLORE) {
            systemPrompt.append("\n\n").append(exploreManager.generateSystemPrompt());
        }
        
        EditorResult repoMap = repoMapPackerService.generateRepoMap();
        if (repoMap.success()) systemPrompt.append("\n\n## REPOSITORY MAP\n").append(repoMap.content()).append("\n");

        systemPrompt.append("\n\n## SESSION CONTEXT\n");
        systemPrompt.append("DOMINANT LANGUAGE: ").append(projectAnalysisService.getDominantLanguage()).append("\n");
        systemPrompt.append("CURRENT PHASE: ").append(currentPhase.name()).append("\n");
        systemPrompt.append("CURRENT ROLE: ").append(currentRole.getTitle()).append("\n");
        systemPrompt.append(currentRole.getSystemPromptPrefix()).append("\n\n");
        systemPrompt.append("You have access to the following JEXL tools:\n").append(jexlServiceRegistry.getDocumentation(currentRole));
        
        String gitStatus = gitService.getStatus();
        if (gitStatus != null && !gitStatus.isEmpty() && !gitStatus.startsWith("Error")) systemPrompt.append("## GIT STATUS\n").append(gitStatus).append("\n\n");
        if (systemPromptText != null) systemPrompt.append("## ADDITIONAL INSTRUCTIONS\n").append(systemPromptText).append("\n\n");
        
        return chatClient.prompt()
                .system(systemPrompt.toString())
                .user(u -> {
                    u.text(message);
                    if (images != null) for (byte[] img : images) u.media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(img));
                })
                .options(GoogleGenAiChatOptions.builder().model(activeModel));
    }
}
