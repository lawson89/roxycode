package org.roxycode.app.ui;

import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Chat panel for Feature/Task mode with dynamic workflow phases. */
@Component
@Lazy
public final class FeatureChatPanel extends ChatPanel {
    private static final long serialVersionUID = 1L;

    public FeatureChatPanel(
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
                ChatModel.TASK_SESSION_PREFIX,
                null,
                true,
                settingsService);
    }

    @EventListener
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
        getController().onWorkflowTransition(event);
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        getController().onPhaseChanged(event);
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        getController().onPlanUpdated(event);
    }
}
