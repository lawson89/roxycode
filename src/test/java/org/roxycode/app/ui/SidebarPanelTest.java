package org.roxycode.app.ui;
 
import org.junit.jupiter.api.Test;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.roxycode.app.ai.WorkflowPhase;
import static org.mockito.Mockito.*;
import java.util.function.Consumer;
 
class SidebarPanelTest {
    @TempDir
    Path tempDir;
 
    private WorkflowService createWorkflowService() {
        return new WorkflowService(mock(PlanService.class), mock(EnvironmentService.class), mock(ApplicationEventPublisher.class));
    }
 
    @Test
    void testSidebarPanelInstantiation() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, createWorkflowService(), card -> {});
        assertNotNull(sidebar);
    }
 
    @Test
    void testSidebarStructure() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings_structure.toml"));
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, createWorkflowService(), card -> {});
        
        Component[] components = sidebar.getComponents();
        
        // Expected: 1 UserProfile, 3 Headers, 8 NavButtons = 12
        assertEquals(12, components.length, "Sidebar should have exactly 12 components");
 
        int headerCount = 0;
        int buttonCount = 0;
        int profileCount = 0;
 
        for (Component c : components) {
            if (c instanceof JLabel) headerCount++;
            if (c instanceof JButton) buttonCount++;
            if (c instanceof UserProfileComponent) profileCount++;
        }
 
        assertEquals(1, profileCount, "Should have 1 user profile");
        assertEquals(3, headerCount, "Should have 3 headers (WORKSPACES, CONTEXT, CONFIG)");
        assertEquals(8, buttonCount, "Should have 8 navigation buttons");
    }
 
    @Test
    @SuppressWarnings("unchecked")
    void testSidebarNavigation() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings_nav.toml"));
        
        WorkflowService workflowService = mock(WorkflowService.class);
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        Consumer<String> navAction = mock(Consumer.class);
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, workflowService, navAction);
        
        JButton qaBtn = null;
        JButton taskBtn = null;
        for (Component c : sidebar.getComponents()) {
            if (c instanceof JButton) {
                JButton b = (JButton) c;
                if ("Codebase Q&A".equals(b.getText())) qaBtn = b;
                if ("Active Task".equals(b.getText())) taskBtn = b;
            }
        }
        
        assertNotNull(qaBtn, "QA Button should be found");
        assertNotNull(taskBtn, "Task Button should be found");
        
        qaBtn.doClick();
        verify(navAction).accept("QA_VIEW");
        
        taskBtn.doClick();
        verify(navAction).accept("TASK_VIEW");
    }
}
