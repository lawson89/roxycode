package org.roxycode.app.ui;

import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

/** Shared mock setup and factory helpers for Chat UI and controller tests. */
abstract class ChatTestFixture {

    protected AiService aiService;
    protected PlanService planService;
    protected MemoryService memoryService;
    protected EnvironmentService environmentService;
    protected ApplicationEventPublisher publisher;
    protected ChatMemory chatMemory;
    protected SettingsService settingsService;
    protected Executor directExecutor;

    @BeforeEach
    void setUpChatFixture() {
        aiService = mock(AiService.class);
        planService = mock(PlanService.class);
        memoryService = mock(MemoryService.class);
        environmentService = mock(EnvironmentService.class);
        publisher = mock(ApplicationEventPublisher.class);
        chatMemory = mock(ChatMemory.class);
        settingsService = mock(SettingsService.class);
        directExecutor = Runnable::run;
    }

    protected ChatPanel createChatPanel() {
        return new ChatPanel(
                aiService, planService, publisher, chatMemory, "test", null, true, null, null);
    }

    protected FeatureChatPanel createFeatureChatPanel(Executor executor) {
        return new FeatureChatPanel(
                aiService, planService, publisher, chatMemory, settingsService, executor);
    }

    protected ExploreChatPanel createExploreChatPanel(Executor executor) {
        return new ExploreChatPanel(
                aiService,
                planService,
                publisher,
                chatMemory,
                settingsService,
                executor,
                memoryService,
                environmentService);
    }

    protected static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    protected static void sendOnEdt(ChatPanel panel, String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.getController().sendMessage(text));
    }

    protected static WorkflowTransitionEvent transitionEvent(
            WorkflowPhase from, WorkflowPhase to, String reason, boolean autonomous) {
        return new WorkflowTransitionEvent(from, to, reason, autonomous);
    }

    protected static ImplementationPlan samplePlan(ImplementationPlan.TechStep... steps) {
        return new ImplementationPlan(
                "Feature X",
                "Build Feature X",
                List.of("Req 1"),
                List.of(steps),
                true,
                null,
                null,
                null,
                List.of());
    }
}
