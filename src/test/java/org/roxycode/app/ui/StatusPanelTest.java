package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Insets;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;
import org.roxycode.app.service.EnvironmentService;

class StatusPanelTest {
    @Test
    void testStatusPanelInstantiation() {
        StatusPanel status = new StatusPanel(new EnvironmentService());
        assertEquals(3, status.getComponentCount());
        status.updateUI();
        assertEquals(new Insets(1, 0, 0, 0), status.getBorder().getBorderInsets(status));

        JLabel memLabel = (JLabel) status.getComponent(2);
        assertTrue(memLabel.getText().contains("Mem:"));
        assertFalse(memLabel.getText().contains("Repo Map:"));

        for (Component comp : status.getComponents()) {
            if (comp instanceof JLabel label) {
                assertFalse(label.getText().contains("Messages:"));
            }
        }
        assertTrue(status.getStatsTimer().isRunning());
        status.removeNotify();
        assertFalse(status.getStatsTimer().isRunning());
        status.addNotify();
        assertTrue(status.getStatsTimer().isRunning());
        status.removeNotify();
    }
}
