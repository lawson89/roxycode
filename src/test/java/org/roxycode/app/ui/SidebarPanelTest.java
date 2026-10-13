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

import static org.mockito.Mockito.mock;
 
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
        
        // Expected: 1 UserProfile, 3 Headers, 11 NavButtons = 15
        assertEquals(15, components.length, "Sidebar should have exactly 15 components");
 
        int headerCount = 0;
        int buttonCount = 0;
        int profileCount = 0;
 
        for (Component c : components) {
            if (c instanceof JLabel) headerCount++;
            if (c instanceof JButton) buttonCount++;
            if (c instanceof UserProfileComponent) profileCount++;
        }
 
        assertEquals(1, profileCount, "Should have 1 user profile");
        assertEquals(3, headerCount, "Should have 3 headers (MAIN, CONTEXT, CONFIG)");
        assertEquals(11, buttonCount, "Should have 11 navigation buttons");
    }

    @Test
    void testPhaseSwitching() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings_phase.toml"));
        
        WorkflowService workflowService = mock(WorkflowService.class);
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, workflowService, card -> {});
        
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
        
        // Click QA Button
        qaBtn.doClick();
        verify(workflowService, atLeastOnce()).switchMode(WorkflowPhase.EXPLORE);
        
        // Reset mock to clear previous interactions if needed, or just check specific transition
        // Click Task Button (while in EXPLORE)
        taskBtn.doClick();
        verify(workflowService).switchMode(WorkflowPhase.PLAN);
        
        // Click Task Button (while NOT in EXPLORE)
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        taskBtn.doClick();
        // verify that switchMode(PLAN) was only called once (the first time)
        verify(workflowService, times(1)).switchMode(WorkflowPhase.PLAN);
    }
}