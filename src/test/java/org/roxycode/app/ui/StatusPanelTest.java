package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatusPanelTest {
    @Test
    void testStatusPanelInstantiation() {
        StatusPanel status = new StatusPanel();
        assertNotNull(status);
    }
}