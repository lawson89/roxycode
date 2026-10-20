package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;

class ChatPanelFactoryTest {

    @Test
    void testCreateChatPanelAndHistoryPanel() {
        AiService aiService = mock(AiService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(new AppSettings());
        PlanService planService = mock(PlanService.class);

        ChatPanelFactory factory =
                new ChatPanelFactory(
                        aiService,
                        workflowService,
                        turnEventBridge,
                        chatMemory,
                        settingsService,
                        planService);

        ChatPanel qaPanel = factory.createChatPanel("qa", WorkflowPhase.EXPLORE, false);
        assertNotNull(qaPanel);
        assertEquals("qa", qaPanel.getModel().getSessionPrefix());
        assertEquals(WorkflowPhase.EXPLORE, qaPanel.getModel().getLockedPhase());
        assertFalse(qaPanel.getModel().isTaskMode());

        ChatPanel taskPanel = factory.createChatPanel("task", null, true);
        assertNotNull(taskPanel);
        assertEquals("task", taskPanel.getModel().getSessionPrefix());
        assertNull(taskPanel.getModel().getLockedPhase());
        assertTrue(taskPanel.getModel().isTaskMode());

        HistoryPanel historyPanel = factory.createHistoryPanel("qa");
        assertNotNull(historyPanel);
    }
}
