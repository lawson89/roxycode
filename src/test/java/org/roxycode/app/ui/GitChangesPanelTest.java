package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GitService;

class GitChangesPanelTest {

    @Test
    void testPanelBackgroundRefreshWithChanges() throws Exception {
        GitService gitService = mock(GitService.class);
        when(gitService.getStatus()).thenReturn(" M src/App.java\n?? src/NewFile.java\n");

        GitChangesPanel panel = new GitChangesPanel(gitService);

        for (int i = 0; i < 50; i++) {
            if ("M src/App.java\n?? src/NewFile.java".equals(panel.getStatusArea().getText())) {
                break;
            }
            Thread.sleep(50);
        }

        assertEquals("M src/App.java\n?? src/NewFile.java", panel.getStatusArea().getText());
        assertFalse(panel.getStatusArea().isEditable());
        verify(gitService, never()).getDiff();
    }

    @Test
    void testPanelBackgroundRefreshWhenWorkingTreeClean() throws Exception {
        GitService gitService = mock(GitService.class);
        when(gitService.getStatus()).thenReturn("   \n  ");

        GitChangesPanel panel = new GitChangesPanel(gitService);

        for (int i = 0; i < 50; i++) {
            if ("Working tree clean".equals(panel.getStatusArea().getText())) {
                break;
            }
            Thread.sleep(50);
        }

        assertEquals("Working tree clean", panel.getStatusArea().getText());

        when(gitService.getStatus()).thenReturn(null);
        String result = panel.refreshAsync().get();
        assertEquals("Working tree clean", result);
    }

    @Test
    void testUpdateUIPreservesContent() throws Exception {
        GitService gitService = mock(GitService.class);
        when(gitService.getStatus()).thenReturn("Status OK");

        GitChangesPanel panel = new GitChangesPanel(gitService);
        for (int i = 0; i < 50; i++) {
            if ("Status OK".equals(panel.getStatusArea().getText())) {
                break;
            }
            Thread.sleep(50);
        }

        assertEquals("Status OK", panel.getStatusArea().getText());

        SwingUtilities.invokeAndWait(panel::updateUI);

        assertEquals("Status OK", panel.getStatusArea().getText());
    }
}
