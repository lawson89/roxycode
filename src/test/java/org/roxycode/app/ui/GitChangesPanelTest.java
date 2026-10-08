package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GitService;

import javax.swing.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitChangesPanelTest {

    @Test
    void testFormatDiffEmptyAndNull() {
        assertEquals("<pre style=\"font-family: monospace; font-size: 11px;\"></pre>",
                GitChangesPanel.formatDiff(null));
        assertEquals("<pre style=\"font-family: monospace; font-size: 11px;\"></pre>",
                GitChangesPanel.formatDiff(""));
    }

    @Test
    void testFormatDiffHighlightsAdditionsAndDeletions() {
        String diff = "--- a/App.java\n+++ b/App.java\n-old code <with tags>\n+new code <with tags>\n context";
        String formatted = GitChangesPanel.formatDiff(diff);

        assertTrue(formatted.startsWith("<pre style=\"font-family: monospace; font-size: 11px;\">"));
        assertTrue(formatted.endsWith("</pre>"));

        // Additions wrapped in green
        assertTrue(formatted.contains("<span style=\"color: #28a745;\">+++ b/App.java</span>"));
        assertTrue(formatted.contains("<span style=\"color: #28a745;\">+new code &lt;with tags&gt;</span>"));

        // Deletions wrapped in red
        assertTrue(formatted.contains("<span style=\"color: #d73a49;\">--- a/App.java</span>"));
        assertTrue(formatted.contains("<span style=\"color: #d73a49;\">-old code &lt;with tags&gt;</span>"));

        // Context line escaped and not wrapped in colored span
        assertTrue(formatted.contains(" context"));
        assertFalse(formatted.contains("<span style=\"color: #28a745;\"> context</span>"));
        assertFalse(formatted.contains("<span style=\"color: #d73a49;\"> context</span>"));
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
        assertEquals("text/html", panel.getDiffArea().getContentType());
        assertFalse(panel.getDiffArea().isEditable());

        String diffText = panel.getDiffArea().getText();
        assertNotNull(diffText);
        assertTrue(diffText.contains("System.out.println();"));
        assertTrue(diffText.contains("log.info();"));
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
        SwingUtilities.invokeAndWait(() -> {
            panel.updateUI();
        });

        String diffText = panel.getDiffArea().getText();
        assertNotNull(diffText);
        assertTrue(diffText.contains("added line"));
    }
}
