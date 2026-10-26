package org.roxycode.app.ui;

import static org.mockito.Mockito.mock;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

/** Shared mock setup and factory helpers for Chat UI and controller tests. */
abstract class ChatTestFixture {

    protected AiService aiService;
    protected PlanService planService;
    protected ApplicationEventPublisher publisher;
    protected ChatMemory chatMemory;
    protected SettingsService settingsService;
    protected Executor directExecutor;

    @BeforeEach
    void setUpChatFixture() {
        aiService = mock(AiService.class);
        planService = mock(PlanService.class);
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
                aiService, planService, publisher, chatMemory, settingsService, executor);
    }
}
