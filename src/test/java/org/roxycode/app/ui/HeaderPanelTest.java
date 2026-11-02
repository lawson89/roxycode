package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class HeaderPanelTest {
    @TempDir
    Path tempDir;

    @Test
    void testHeaderDisplaysProjectBranchModelThemeToggleAndAsyncSwitch() throws Exception {
        SettingsService ss = spy(new SettingsService(null, null));
        ss.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        ProjectService ps = spy(new ProjectService(ss, null));
        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("feature/async-branch");

        HeaderPanel header = new HeaderPanel(ps, gitService, ss);
        assertTrue(header.noProjectNoticeLabel.isVisible());
        assertEquals(HeaderPanel.NO_PROJECT_NOTICE_TEXT, header.noProjectNoticeLabel.getText());
        assertEquals(HeaderPanel.SELECT_PROJECT_TEXT, header.changeProjectButton.getText());
        SwingTestUtils.awaitFuture(header.updateBranchLabel());
        assertEquals("feature/async-branch", header.branchLabel.getText());
        assertTrue(header.branchLabel.isVisible());

        Path newRoot = tempDir.resolve("updated-proj");
        ps.setCurrentProjectRoot(newRoot);
        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiModel("gemini-4-argon");

        Thread bg = new Thread(() -> {
            header.onProjectChanged(new ProjectChangedEvent(newRoot));
            header.onSettingsChanged(new SettingsChangedEvent(updatedSettings));
        });
        bg.start();
        bg.join(5000);
        SwingTestUtils.flushEdt();

        assertEquals("updated-proj", header.projectLabel.getText());
        assertFalse(header.noProjectNoticeLabel.isVisible());
        assertEquals(HeaderPanel.CHANGE_PROJECT_TEXT, header.changeProjectButton.getText());
        assertEquals("gemini-4-argon", header.modelLabel.getText());

        ps.setProjectGeminiModel("gemini-project-override");
        SwingTestUtils.runOnEdt(() -> header.onSettingsChanged(new SettingsChangedEvent(updatedSettings)));
        assertEquals("gemini-project-override", header.modelLabel.getText());

        try {
            header.themeToggleButton.doClick();
            assertEquals("FlatLaf Dark", ss.getSettings().getTheme());
            header.themeToggleButton.doClick();
            assertEquals("FlatLaf Light", ss.getSettings().getTheme());
        } finally {
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }

        AtomicBoolean calledOnEdt = new AtomicBoolean(true);
        doAnswer(inv -> {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return inv.callRealMethod();
        }).when(ps).setCurrentProjectRoot(any());
        SwingTestUtils.awaitFuture(
                SwingTestUtils.callOnEdt(() -> header.switchProjectAsync(tempDir.resolve("chosen-project"))));
        assertFalse(calledOnEdt.get());
    }
}
