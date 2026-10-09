package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.ai.chat.memory.ChatMemory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusPanelTest {
    @Test
    void testStatusPanelInstantiation() {
        EnvironmentService envService = new EnvironmentService();
        ChatMemory chatMemory = mock(ChatMemory.class);
        StatusPanel status = new StatusPanel(envService, chatMemory);
        assertNotNull(status);
    }
}