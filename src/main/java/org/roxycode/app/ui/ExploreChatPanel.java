package org.roxycode.app.ui;

import java.util.concurrent.Executor;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Chat panel for Explore (Q&A / Read-Only Mentor) mode. */
@Component
@Lazy
public final class ExploreChatPanel extends ChatPanel {
    private static final long serialVersionUID = 1L;

    public ExploreChatPanel(
            AiService aiService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory,
            SettingsService settingsService) {
        super(
                aiService,
                planService,
                eventPublisher,
                chatMemory,
                ChatModel.QA_SESSION_PREFIX,
                WorkflowPhase.EXPLORE,
                false,
                settingsService);
    }

    @Autowired
    public ExploreChatPanel(
            AiService aiService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory,
            SettingsService settingsService,
            @Qualifier("chatTurnExecutor") Executor turnExecutor) {
        super(
                aiService,
                planService,
                eventPublisher,
                chatMemory,
                ChatModel.QA_SESSION_PREFIX,
                WorkflowPhase.EXPLORE,
                false,
                settingsService,
                turnExecutor);
    }
}
