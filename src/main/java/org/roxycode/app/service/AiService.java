package org.roxycode.app.service;

import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * Service for interacting with the AI Chat Model using the modern ChatClient fluent API.
 */
@Service
public class AiService {

    private static final String CORE_WORKFLOW_PROMPT = """
            # ROXY CORE WORKFLOW PROTOCOL
            
            You are operating in a multi-role agent workflow. Your personality, goals, and available actions are determined by your current ROLE and the current workflow PHASE.
            
            ## OPERATIONAL CONSTRAINTS
            1. PHASE INTEGRITY: You MUST NOT skip ahead or perform actions reserved for future phases.
            2. ROLE ADHERENCE: You must strictly embody the current Role provided in the Dynamic Context.
            3. HUMAN-IN-THE-LOOP (HITL): Major transitions and plan approvals require explicit human confirmation.
            4. TOOL USAGE: Use the JEXL tools provided to perform your tasks.
            
            ## WORKFLOW PHASES
            - EXPLORE: Broad codebase exploration and context gathering.
            - DISCOVERY: Gathering functional requirements and defining project goals.
            - DESIGN: Creating technical specifications and step-by-step implementation plans.
            - DEVELOPMENT: Writing, testing, and verifying code based on an approved plan.
            - VERIFICATION: Final review and quality assurance.
            """;

    private final ChatClient chatClient;
    private final SettingsService settingsService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final WorkflowService workflowService;

    public AiService(ChatClient.Builder chatClientBuilder, SettingsService settingsService, 
                     JexlServiceRegistry jexlServiceRegistry, JexlTool jexlTool,
                     WorkflowService workflowService) {
        this.settingsService = settingsService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.workflowService = workflowService;
        this.chatClient = chatClientBuilder
                .defaultTools(jexlTool)
                .build();
    }

    /**
     * Sends a prompt to the AI and returns the response content.
     * @param message The user message.
     * @return The AI response content.
     */
    public String chat(String message) {
        return chat(message, null);
    }

    /**
     * Sends a prompt to the AI with a custom system prompt and returns the response content.
     * @param message The user message.
     * @param systemPromptText The custom system prompt.
     * @return The AI response content.
     */
    public String chat(String message, String systemPromptText) {
        String activeModel = settingsService.getSettings().getGeminiModel();
        String jexlDocs = jexlServiceRegistry.getDocumentation();
        
        WorkflowPhase currentPhase = workflowService.getCurrentPhase();
        AgentRole currentRole = currentPhase.getRole();
        
        StringBuilder systemPrompt = new StringBuilder(CORE_WORKFLOW_PROMPT);
        systemPrompt.append("\n\n## DYNAMIC CONTEXT\n");
        systemPrompt.append("CURRENT PHASE: ").append(currentPhase.name()).append(" (").append(currentPhase.getDisplayName()).append(")\n");
        systemPrompt.append("CURRENT ROLE: ").append(currentRole.getTitle()).append("\n");
        systemPrompt.append(currentRole.getSystemPromptPrefix()).append("\n\n");
        
        if (systemPromptText != null) {
            systemPrompt.append(systemPromptText).append("\n\n");
        }
        
        systemPrompt.append("You have access to the following JEXL tools:\n").append(jexlDocs);
        
        return chatClient.prompt()
                .system(systemPrompt.toString())
                .user(message)
                .options(GoogleGenAiChatOptions.builder().model(activeModel))
                .call()
                .content();
    }
}
