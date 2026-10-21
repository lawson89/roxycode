package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;
import org.roxycode.app.service.EnvironmentService;

class StatusPanelTest {
    @Test
    void testStatusPanelInstantiation() {
        EnvironmentService envService = new EnvironmentService();
        StatusPanel status = new StatusPanel(envService);
                assertNotNull(status);
        assertEquals(3, status.getComponentCount());
        status.updateUI();
        assertEquals(
                new java.awt.Insets(1, 0, 0, 0),
                status.getBorder().getBorderInsets(status),
                "StatusPanel should maintain a 1px top MatteBorder across updateUI()");

        for (Component comp : status.getComponents()) {
            if (comp instanceof JLabel label) {
                assertFalse(
                        label.getText().contains("Messages:"),
                        "Status bar should not contain Messages count");
            }
        }
    }
}
