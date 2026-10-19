package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GitService;

class GitChangesPanelTest {

  @Test
  void testFormatDiffEmptyAndNull() {
    assertEquals(
        "<pre style='font-family: monospace; font-size: 11px;'></pre>",
        GitChangesPanel.formatDiff(null));
    assertEquals(
        "<pre style='font-family: monospace; font-size: 11px;'></pre>",
        GitChangesPanel.formatDiff(""));
  }

  @Test
  void testFormatDiffHighlightsAdditionsAndDeletionsAndExcludesHeadersAndCrlf() {
    String diff =
        "--- a/App.java\r\n+++ b/App.java\r\n-old code <with tags>\r\n+new code <with tags>\r\n context";
    String formatted = GitChangesPanel.formatDiff(diff);

    assertTrue(formatted.startsWith("<pre style='font-family: monospace; font-size: 11px;'>"));
    assertTrue(formatted.endsWith("</pre>"));
    assertFalse(formatted.contains("\r"), "Formatted diff should not contain CR characters");

    // File headers should NOT be wrapped in colored spans
    assertTrue(formatted.contains("--- a/App.java"));
    assertTrue(formatted.contains("+++ b/App.java"));
    assertFalse(formatted.contains("<span style='color: #28a745;'>+++ b/App.java</span>"));
    assertFalse(formatted.contains("<span style='color: #d73a49;'>--- a/App.java</span>"));

    // Additions wrapped in green
    assertTrue(
        formatted.contains("<span style='color: #28a745;'>+new code &lt;with tags&gt;</span>"));

    // Deletions wrapped in red
    assertTrue(
        formatted.contains("<span style='color: #d73a49;'>-old code &lt;with tags&gt;</span>"));

    // Context line escaped and not wrapped in colored span
    assertTrue(formatted.contains(" context"));
    assertFalse(formatted.contains("<span style='color: #28a745;'> context</span>"));
    assertFalse(formatted.contains("<span style='color: #d73a49;'> context</span>"));
  }

  @Test
  void testParseStatusEntriesAndExtractFilePath() {
    assertTrue(GitChangesPanel.parseStatusEntries(null).isEmpty());
    assertTrue(GitChangesPanel.parseStatusEntries("   ").isEmpty());
    assertTrue(GitChangesPanel.parseStatusEntries("No active project").isEmpty());
    assertTrue(GitChangesPanel.parseStatusEntries("Not a git repository").isEmpty());
    assertTrue(GitChangesPanel.parseStatusEntries("Error (128): fatal").isEmpty());

    String porcelain =
        " M src/App.java\r\n?? src/NewFile.java\nR  src/Old.java -> src/Renamed.java\n";
    List<String> entries = GitChangesPanel.parseStatusEntries(porcelain);
    assertEquals(
        List.of("M src/App.java", "?? src/NewFile.java", "R  src/Old.java -> src/Renamed.java"),
        entries);

    assertEquals("src/App.java", GitChangesPanel.extractFilePathFromStatusLine("M src/App.java"));
    assertEquals("src/App.java", GitChangesPanel.extractFilePathFromStatusLine(" M src/App.java"));
    assertEquals(
        "src/NewFile.java", GitChangesPanel.extractFilePathFromStatusLine("?? src/NewFile.java"));
    assertEquals(
        "src/Renamed.java",
        GitChangesPanel.extractFilePathFromStatusLine("R  src/Old.java -> src/Renamed.java"));
  }

  @Test
  void testPanelBackgroundRefresh() throws Exception {
    GitService gitService = mock(GitService.class);
    when(gitService.getStatus()).thenReturn(" M src/App.java");
    when(gitService.getDiff()).thenReturn("+System.out.println();\n-log.info();");

    GitChangesPanel panel = new GitChangesPanel(gitService);

    // Allow initial refresh from constructor or subsequent refresh to complete
    for (int i = 0; i < 50; i++) {
      if (" M src/App.java".equals(panel.getStatusArea().getText())) {
        break;
      }
      Thread.sleep(50);
    }

    assertEquals(" M src/App.java", panel.getStatusArea().getText());
    assertEquals(2, panel.getFileListModel().getSize());
    assertEquals("All Changes", panel.getFileListModel().getElementAt(0));
    assertEquals("M src/App.java", panel.getFileListModel().getElementAt(1));
    assertEquals("text/html", panel.getDiffArea().getContentType());
    assertFalse(panel.getDiffArea().isEditable());

    String diffText = panel.getDiffArea().getText();
    assertNotNull(diffText);
    assertTrue(diffText.contains("System.out.println();"));
    assertTrue(diffText.contains("log.info();"));
  }

  @Test
  void testSelectingFileFiltersDiffAndAllChangesRestoresFullDiff() throws Exception {
    GitService gitService = mock(GitService.class);
    when(gitService.getStatus()).thenReturn(" M src/App.java\n?? src/Other.java");
    when(gitService.getDiff()).thenReturn("+full repo diff line");
    when(gitService.getDiff("src/App.java")).thenReturn("+specific app.java diff");

    GitChangesPanel panel = new GitChangesPanel(gitService);

    for (int i = 0; i < 50; i++) {
      if (panel.getFileListModel().getSize() == 3) {
        break;
      }
      Thread.sleep(50);
    }

    assertEquals(3, panel.getFileListModel().getSize());
    assertEquals("All Changes", panel.getFileList().getSelectedValue());
    assertTrue(panel.getDiffArea().getText().contains("full repo diff line"));

    // Select specific file "M src/App.java" at index 1
    SwingUtilities.invokeAndWait(() -> panel.getFileList().setSelectedIndex(1));

    for (int i = 0; i < 50; i++) {
      if (panel.getDiffArea().getText().contains("specific app.java diff")) {
        break;
      }
      Thread.sleep(50);
    }

    verify(gitService, atLeastOnce()).getDiff("src/App.java");
    assertTrue(panel.getDiffArea().getText().contains("specific app.java diff"));

    // Verify refresh preserves the selected file if still present
    SwingUtilities.invokeAndWait(panel::refresh);
    for (int i = 0; i < 50; i++) {
      if ("M src/App.java".equals(panel.getFileList().getSelectedValue())) {
        break;
      }
      Thread.sleep(50);
    }
    assertEquals("M src/App.java", panel.getFileList().getSelectedValue());

    // Select "All Changes" at index 0 to restore full diff
    SwingUtilities.invokeAndWait(() -> panel.getFileList().setSelectedIndex(0));

    for (int i = 0; i < 50; i++) {
      if (panel.getDiffArea().getText().contains("full repo diff line")) {
        break;
      }
      Thread.sleep(50);
    }

    assertTrue(panel.getDiffArea().getText().contains("full repo diff line"));
  }

  @Test
  void testUpdateUIPreservesContent() throws Exception {
    GitService gitService = mock(GitService.class);
    when(gitService.getStatus()).thenReturn("Status OK");
    when(gitService.getDiff()).thenReturn("+added line");

    GitChangesPanel panel = new GitChangesPanel(gitService);
    for (int i = 0; i < 50; i++) {
      if ("Status OK".equals(panel.getStatusArea().getText())) {
        break;
      }
      Thread.sleep(50);
    }

    assertEquals("Status OK", panel.getStatusArea().getText());

    // Invoke updateUI() to simulate theme / look & feel change
    SwingUtilities.invokeAndWait(
        () -> {
          panel.updateUI();
        });

    String diffText = panel.getDiffArea().getText();
    assertNotNull(diffText);
    assertTrue(diffText.contains("added line"));
  }
}
