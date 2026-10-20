package org.roxycode.app.ui;

import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Factory for creating ChatPanel and HistoryPanel instances with shared chat dependencies. */
@Component
@Lazy
public class ChatPanelFactory {

    private final AiService aiService;
    private final WorkflowService workflowService;
    private final TurnEventBridge turnEventBridge;
    private final ChatMemory chatMemory;
    private final SettingsService settingsService;
    private final PlanService planService;

    public ChatPanelFactory(
            AiService aiService,
            WorkflowService workflowService,
            TurnEventBridge turnEventBridge,
            ChatMemory chatMemory,
            SettingsService settingsService,
            PlanService planService) {
        this.aiService = aiService;
        this.workflowService = workflowService;
        this.turnEventBridge = turnEventBridge;
        this.chatMemory = chatMemory;
        this.settingsService = settingsService;
        this.planService = planService;
    }

    public ChatPanel createChatPanel(
            String sessionPrefix, WorkflowPhase lockedPhase, boolean isTaskMode) {
        return new ChatPanel(
                aiService,
                workflowService,
                turnEventBridge,
                chatMemory,
                sessionPrefix,
                lockedPhase,
                isTaskMode,
                settingsService,
                planService);
    }

    public HistoryPanel createHistoryPanel(String sessionPrefix) {
        return new HistoryPanel(turnEventBridge, chatMemory, sessionPrefix);
    }
}
