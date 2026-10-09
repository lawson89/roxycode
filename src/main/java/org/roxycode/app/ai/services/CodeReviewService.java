package org.roxycode.app.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@AgentService(value = "codeReviewService", roles = {"SENIOR_DEVELOPER", "TECHNICAL_MENTOR"})
@AgentDoc("Executes an independent sub-agent review to audit git changes against implementation requirements.")
public class CodeReviewService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);
    private final ChatClient reviewChatClient;
    private final SettingsService settingsService;
    private final GitService gitService;
    private final PlanManagerService planManager;
    private final GenericBuildToolService buildToolService;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;

    public record ReviewResult(boolean approved, String summary, String[] defects) {}

    public CodeReviewService(ChatClient.Builder builder, SettingsService settingsService,
                             GitService gitService, PlanManagerService planManager,
                             GenericBuildToolService buildToolService, @Lazy JexlTool jexlTool,
                             ChatMemory chatMemory, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.gitService = gitService;
        this.planManager = planManager;
        this.buildToolService = buildToolService;
        this.chatMemory = chatMemory;
        this.objectMapper = objectMapper;

        // Configure ChatClient with JexlTool for read-only codebase navigation
        this.reviewChatClient = builder
                .defaultTools(jexlTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @AgentDoc("Executes an independent, stateless code review audit. Returns a JSON string with approved status, summary, and defects.")
    public String performAudit() {
        String tempSessionId = "review-session-" + UUID.randomUUID();
        ImplementationPlan plan = planManager.getCurrentPlan();
        String diff = gitService.getDiff();
        BuildResult build = buildToolService.buildAndTest();
        String activeModel = settingsService.getSettings().getGeminiModel();

        String systemPrompt = """
            You are an uncompromising Senior Software Architect performing an adversarial code review.
            You have access to read-only JEXL tools (`fileReadService`, `grepService`, `gitService`, `buildToolService`).
            
            ### REVIEW PROTOCOL:
            1. Review the git diff, build/test results, and plan requirements below.
            2. Use `fileReadService` or `grepService` to check un-diffed files or callers if you suspect breaking changes elsewhere.
            3. Verify that all functional and technical requirements are satisfied.
            4. Respond ONLY with a JSON object in this exact schema:
               {
                 "approved": true/false,
                 "summary": "Short explanation of findings",
                 "defects": ["defect 1", "defect 2"]
               }
            """;

        String userPrompt = """
            ### IMPLEMENTATION PLAN
            Title: %s
            Goal: %s
            Requirements: %s
            
            ### BUILD & TEST RESULTS
            Success: %s
            Output: %s
            
            ### GIT DIFF (UNSTAGED CHANGES)
            %s
            """.formatted(
                plan != null ? plan.title() : "None",
                plan != null ? plan.goal() : "None",
                plan != null ? plan.requirements() : "None",
                build.success(),
                build.output(),
                diff.isBlank() ? "No changes found" : diff
            );

        try {
            log.info("Executing Code Review Sub-Agent audit...");
            String response = reviewChatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .advisors(a -> a.param("chat_memory_conversation_id", tempSessionId))
                    .options(GoogleGenAiChatOptions.builder().model(activeModel))
                    .call()
                    .content();

            return response;
        } catch (Exception e) {
            log.error("Code review audit failed: {}", e.getMessage(), e);
            return "{\"approved\": false, \"summary\": \"Code review execution failed\", \"defects\": [\"Review sub-agent exception\"]}";
        } finally {
            // Clean up temporary chat memory thread
            chatMemory.clear(tempSessionId);
        }
    }
}
