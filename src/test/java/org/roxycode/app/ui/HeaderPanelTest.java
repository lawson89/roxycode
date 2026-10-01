package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ai.services.GitService;
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

    private GitService createGitService(ProjectService ps) {
        return new GitService(ps);
    }

    @Test
    void testHeaderPanelInstantiation() {
        ProjectService ps = createProjectService();
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps));
        assertNotNull(header);
    }

    @Test
    void testSearchFieldIsRemoved() {
        ProjectService ps = createProjectService();
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps));
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
        ProjectService ps = createProjectService();
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps));
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