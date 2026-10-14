package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.SettingsService;
import org.springframework.context.ApplicationEventPublisher;

class SidebarPanelTest {
  @TempDir Path tempDir;

  private WorkflowService createWorkflowService() {
    return new WorkflowService(
        mock(PlanService.class),
        mock(EnvironmentService.class),
        mock(ApplicationEventPublisher.class));
  }

  @Test
  void testSidebarPanelInstantiation() {
    SettingsService settingsService = new SettingsService();
    settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

    SidebarPanel sidebar = new SidebarPanel(createWorkflowService(), card -> {});
    assertNotNull(sidebar);
  }

  @Test
  void testSidebarStructure() {
    SettingsService settingsService = new SettingsService();
    settingsService.setSettingsPathOverride(tempDir.resolve("settings_structure.toml"));

    SidebarPanel sidebar = new SidebarPanel(createWorkflowService(), card -> {});

    Component[] components = sidebar.getComponents();

        // Expected: 3 Headers, 8 NavButtons = 11
    assertEquals(11, components.length, "Sidebar should have exactly 11 components");

    int headerCount = 0;
    int buttonCount = 0;

    for (Component c : components) {
      if (c instanceof JLabel) headerCount++;
      if (c instanceof JButton) buttonCount++;
    }

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

    SidebarPanel sidebar = new SidebarPanel(workflowService, navAction);

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

    assertTrue(qaBtn.isSelected(), "Codebase Q&A button should be selected on startup");
    assertFalse(taskBtn.isSelected(), "Active Task button should not be selected on startup");

    qaBtn.doClick();
    verify(navAction).accept("QA_VIEW");

    taskBtn.doClick();
    verify(navAction).accept("TASK_VIEW");
  }
}