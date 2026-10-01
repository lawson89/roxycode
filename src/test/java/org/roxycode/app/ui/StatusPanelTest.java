package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.service.EnvironmentService;
import static org.junit.jupiter.api.Assertions.*;

class StatusPanelTest {
    @Test
    void testStatusPanelInstantiation() {
        EnvironmentService envService = new EnvironmentService();
        StatusPanel status = new StatusPanel(envService);
        assertNotNull(status);
    }
}