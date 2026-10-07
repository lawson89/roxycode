package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.TurnEventBridge;
import static org.mockito.Mockito.mock;
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

    private WorkflowService createWorkflowService() {
        return new WorkflowService(null);
    }

    @Test
    void testHeaderPanelInstantiation() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, mock(TurnEventBridge.class), name -> {});
        assertNotNull(header);
    }

    @Test
    void testSearchFieldIsRemoved() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, mock(TurnEventBridge.class), name -> {});
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
    void testUtilityIconsAreRemoved() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, mock(TurnEventBridge.class), name -> {});
        
        boolean settingsFound = false;
        
        for (Component comp : header.getComponents()) {
            if (comp instanceof JPanel) {
                JPanel panel = (JPanel) comp;
                for (Component sub : panel.getComponents()) {
                    if (sub instanceof JButton && "Settings".equals(((JButton) sub).getToolTipText())) {
                        settingsFound = true;
                    }
                }
            }
        }
        assertFalse(settingsFound, "Settings button should be removed");
    }

    @Test
    void testOpenButtonIsPresent() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, mock(TurnEventBridge.class), name -> {});
        
        JButton openButton = null;
        
        // Find in nested panels
        for (Component comp : header.getComponents()) {
            if (comp instanceof JPanel) {
                JPanel p1 = (JPanel) comp;
                for (Component c1 : p1.getComponents()) {
                    if (c1 instanceof JPanel) {
                        JPanel p2 = (JPanel) c1;
                        for (Component c2 : p2.getComponents()) {
                            if (c2 instanceof JButton && "Open Project".equals(((JButton) c2).getToolTipText())) {
                                openButton = (JButton) c2;
                            }
                        }
                    }
                }
            }
        }
        
        assertNotNull(openButton, "Open Project icon button should be present with correct tooltip");
    }
}