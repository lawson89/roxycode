package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import javax.swing.JTextField;
import javax.swing.JLabel;

class HeaderPanelTest {
    @Test
    void testHeaderPanelInstantiation() {
        HeaderPanel header = new HeaderPanel();
        assertNotNull(header);
    }

    @Test
    void testSearchFieldIsRemoved() {
        HeaderPanel header = new HeaderPanel();
        boolean searchFieldFound = false;
        for (Component comp : header.getComponents()) {
            if (comp instanceof JTextField) {
                searchFieldFound = true;
                break;
            }
        }
        assertFalse(searchFieldFound, "Search field should be removed from HeaderPanel");
    }

    @Test
    void testNotificationIconIsPresent() {
        HeaderPanel header = new HeaderPanel();
        boolean notificationIconFound = false;
        for (Component comp : header.getComponents()) {
            if (comp instanceof JLabel && ((JLabel) comp).getText().equals("🔔")) {
                notificationIconFound = true;
                break;
            }
        }
        assertTrue(notificationIconFound, "Notification icon should be present in HeaderPanel");
    }
}