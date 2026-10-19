package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class HeaderPanelTest {
  @TempDir Path tempDir;

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
  void testModeTogglesAndWorkflowIndicatorsAreRemovedFromHeader() {
    SettingsService ss = createSettingsService();
    ProjectService ps = createProjectService(ss);
    HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);

    Component toggle = findComponent(header, JToggleButton.class, c -> true);
    assertNull(toggle, "Mode toggle buttons should be removed from HeaderPanel");

    Component indicator = findComponent(header, WorkflowPhaseIndicator.class, c -> true);
    assertNull(indicator, "WorkflowPhaseIndicator should be removed from HeaderPanel");
  }

  @Test
  void testOpenButtonIsPresent() {
    SettingsService ss = createSettingsService();
    ProjectService ps = createProjectService(ss);
    HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);

    JButton openButton =
        (JButton)
            findComponent(
                header, JButton.class, c -> "Open Project".equals(((JButton) c).getToolTipText()));
    assertNotNull(openButton);
  }

  @Test
  void testHeaderLayoutInsetsAndInlineLeftSection() {
    SettingsService ss = createSettingsService();
    ProjectService ps = createProjectService(ss);
    HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);
    net.miginfocom.swing.MigLayout layout = (net.miginfocom.swing.MigLayout) header.getLayout();
    Object constraints = layout.getLayoutConstraints();
    assertTrue(constraints.toString().contains("insets 2 15 2 15"));

    JButton openButton =
        (JButton)
            findComponent(
                header, JButton.class, c -> "Open Project".equals(((JButton) c).getToolTipText()));
    assertNotNull(openButton);
    Container leftPanel = openButton.getParent();
    assertEquals(
        3,
        leftPanel.getComponentCount(),
        "leftPanel should have projectLabel, openButton, and branchLabel inline");
  }

  private Component findComponent(
      Container container, Class<?> clazz, java.util.function.Predicate<Component> predicate) {
    for (Component comp : container.getComponents()) {
      if (clazz.isInstance(comp) && predicate.test(comp)) {
        return comp;
      }
      if (comp instanceof Container) {
        Component found = findComponent((Container) comp, clazz, predicate);
        if (found != null) return found;
      }
    }
    return null;
  }
}
