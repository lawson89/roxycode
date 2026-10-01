package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.nio.file.Path;
import javax.swing.JTextField;
import javax.swing.JLabel;

class HeaderPanelTest {
    @TempDir
    Path tempDir;

    private ProjectService createProjectService() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        return new ProjectService(settingsService);
    }

    @Test
    void testHeaderPanelInstantiation() {
        HeaderPanel header = new HeaderPanel(createProjectService());
        assertNotNull(header);
    }

    @Test
    void testSearchFieldIsRemoved() {
        HeaderPanel header = new HeaderPanel(createProjectService());
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
        HeaderPanel header = new HeaderPanel(createProjectService());
        boolean notificationIconFound = false;
        for (Component comp : header.getComponents()) {
            // Search recursively if needed, but HeaderPanel adds it directly or to nested panels
            // Based on current implementation, it's added to HeaderPanel itself
            if (comp instanceof JLabel && ((JLabel) comp).getText().equals("🔔")) {
                notificationIconFound = true;
                break;
            }
        }
        assertTrue(notificationIconFound, "Notification icon should be present in HeaderPanel");
    }
}