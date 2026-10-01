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
import javax.swing.JPanel;
import javax.swing.JButton;

class HeaderPanelTest {
    @TempDir
    Path tempDir;

    private SettingsService createSettingsService() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        return settingsService;
    }

    private ProjectService createProjectService(SettingsService ss) {
        return new ProjectService(ss);
    }

    private GitService createGitService(ProjectService ps) {
        return new GitService(ps);
    }

    @Test
    void testHeaderPanelInstantiation() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);
        assertNotNull(header);
    }

    @Test
    void testSearchFieldIsRemoved() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);
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
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);
        boolean notificationIconFound = false;
        for (Component comp : header.getComponents()) {
            if (comp instanceof JLabel && ((JLabel) comp).getText().equals("🔔")) {
                notificationIconFound = true;
                break;
            }
        }
        assertTrue(notificationIconFound, "Notification icon should be present in HeaderPanel");
    }

    @Test
    void testOpenButtonIsInsideProjectInfoPanel() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);
        
        JButton openButton = null;
        JPanel projectInfoPanel = null;
        
        for (Component comp : header.getComponents()) {
            if (comp instanceof JPanel) {
                projectInfoPanel = (JPanel) comp;
                for (Component subComp : projectInfoPanel.getComponents()) {
                    if (subComp instanceof JButton && ((JButton) subComp).getText().equals("Open Project")) {
                        openButton = (JButton) subComp;
                        break;
                    }
                }
            }
        }
        
        assertNotNull(projectInfoPanel, "Project info panel should be present");
        assertNotNull(openButton, "Open Project button should be inside projectInfoPanel");
    }
}
