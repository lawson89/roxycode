package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.Optional;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.PromptService;

@ExtendWith(MockitoExtension.class)
class CodebaseCachePanelTest {

  @Mock private PromptService promptService;
  @Mock private RepoMapPackerService packerService;
  @Mock private ProjectCacheMetaService metaService;
  @Mock private GeminiCacheService geminiCacheService;

  private CodebaseCachePanel panel;

  @BeforeEach
  void setUp() {
    lenient().when(metaService.loadMeta()).thenReturn(Optional.empty());
    lenient().when(metaService.loadRepoCache()).thenReturn(Optional.empty());

    panel = new CodebaseCachePanel(packerService, metaService, geminiCacheService, promptService);
  }

  @Test
  void testButtonsExist() {
    JButton packButton = findButton(panel, "Update Implicit Cache");
    assertNotNull(packButton, "Update button should exist");
  }

  @Test
  void testRepoMapAreaExists() {
    JTextArea textArea = findComponent(panel, JTextArea.class);
    assertNotNull(textArea, "JTextArea should exist for repo map content");
  }

  @Test
  void testRefreshStatusRunsAsyncAndUpdatesAreas() throws Exception {
    // Ensure initial constructor refreshStatus has finished before re-stubbing
    // mocks
    for (int i = 0; i < 20; i++) {
      SwingUtilities.invokeAndWait(() -> {});
      if (!"Status: Unknown".equals(panel.getStatusLabel().getText())) {
        break;
      }
      Thread.sleep(10);
    }

    ProjectCacheMeta meta =
        new ProjectCacheMeta("roxycode", LocalDateTime.of(2026, 1, 1, 12, 0), 500L);
    when(metaService.loadMeta()).thenReturn(Optional.of(meta));
    when(metaService.loadRepoCache()).thenReturn(Optional.of("cached repo map"));
    when(promptService.getStableContext()).thenReturn("stable prompt");
    when(geminiCacheService.estimateTokens("stable prompt")).thenReturn(100L);
    when(promptService.getSessionContext()).thenReturn("session prompt");

    panel.refreshStatus().get();

    for (int i = 0; i < 50; i++) {
      SwingUtilities.invokeAndWait(() -> {});
      if ("session prompt".equals(panel.getSessionContextArea().getText())) {
        break;
      }
      Thread.sleep(20);
    }

    assertTrue(panel.getStatusLabel().getText().contains("Total: 600"));
    assertEquals("cached repo map", panel.getRepoMapArea().getText());
    assertEquals("stable prompt", panel.getStableContextArea().getText());
    assertEquals("session prompt", panel.getSessionContextArea().getText());
  }

  private JButton findButton(Container container, String text) {
    for (Component comp : container.getComponents()) {
      if (comp instanceof JButton && text.equals(((JButton) comp).getText())) {
        return (JButton) comp;
      }
      if (comp instanceof Container) {
        JButton res = findButton((Container) comp, text);
        if (res != null) {
          return res;
        }
      }
    }
    return null;
  }

  private <T> T findComponent(Container container, Class<T> clazz) {
    for (Component comp : container.getComponents()) {
      if (clazz.isInstance(comp)) {
        return clazz.cast(comp);
      }
      if (comp instanceof Container) {
        T res = findComponent((Container) comp, clazz);
        if (res != null) {
          return res;
        }
      }
    }
    return null;
  }
}
