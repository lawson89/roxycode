package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JLabel;
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

    private SettingsService createSettingsService() {
        SettingsService ss = new SettingsService(null, null);
        ss.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        return ss;
    }

    private GitService createGitService() {
        GitService gitService = mock(GitService.class);
        lenient().when(gitService.getCurrentBranch()).thenReturn("Not a git repository");
        return gitService;
    }

    @Test
    void testHeaderDisplaysProjectBranchAndModel() {
        SettingsService ss = createSettingsService();
        ProjectService ps = new ProjectService(ss, null);
        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("feature/async-branch");

        HeaderPanel header = new HeaderPanel(ps, gitService, ss);
        SwingTestUtils.awaitWorker(header.updateBranchLabel());
        SwingTestUtils.awaitCondition(() -> "feature/async-branch".equals(header.getBranchLabel().getText()));
        assertTrue(header.getBranchLabel().isVisible());

        Path newRoot = tempDir.resolve("updated-proj");
        ps.setCurrentProjectRoot(newRoot);
        SwingTestUtils.runOnEdt(() -> header.onProjectChanged(new ProjectChangedEvent(newRoot)));

        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiModel("gemini-4-argon");
        SwingTestUtils.runOnEdt(() -> header.onSettingsChanged(new SettingsChangedEvent(updatedSettings)));

        assertEquals("updated-proj", header.getProjectLabel().getText());
        assertEquals("gemini-4-argon", header.getModelLabel().getText());
    }

    @Test
    void testOnProjectChangedAndOnSettingsChangedOffEdtDispatchToEdt() throws Exception {
        SettingsService ss = createSettingsService();
        ProjectService ps = new ProjectService(ss, null);
        HeaderPanel header = new HeaderPanel(ps, createGitService(), ss);

        Path newRoot = tempDir.resolve("off-edt-proj");
        ps.setCurrentProjectRoot(newRoot);
        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiModel("gemini-off-edt");

        Thread backgroundThread = new Thread(() -> {
            header.onProjectChanged(new ProjectChangedEvent(newRoot));
            header.onSettingsChanged(new SettingsChangedEvent(updatedSettings));
        });
        backgroundThread.start();
        backgroundThread.join(5000);
        SwingTestUtils.flushEdt();

        assertNotNull(SwingTestUtils.findComponent(header, JLabel.class, c -> "off-edt-proj".equals(c.getText())));
        assertNotNull(SwingTestUtils.findComponent(header, JLabel.class, c -> "gemini-off-edt".equals(c.getText())));
    }

    @Test
    void testThemeToggleButtonSwitchesAndSavesTheme() {
        SettingsService ss = spy(createSettingsService());
        HeaderPanel header = new HeaderPanel(new ProjectService(ss, null), createGitService(), ss);

        JButton themeButton = header.getThemeToggleButton();
        assertNotNull(themeButton);
        assertEquals("FlatLaf Light", ss.getSettings().getTheme());

        themeButton.doClick();
        assertEquals("FlatLaf Dark", ss.getSettings().getTheme());
        verify(ss, times(1)).saveSettings();

        themeButton.doClick();
        assertEquals("FlatLaf Light", ss.getSettings().getTheme());
        verify(ss, times(2)).saveSettings();
    }

    @Test
    void testSwitchProjectAsyncRunsOffEdt() {
        SettingsService ss = createSettingsService();
        ProjectService ps = spy(new ProjectService(ss, null));
        HeaderPanel header = new HeaderPanel(ps, createGitService(), ss);
        AtomicBoolean calledOnEdt = new AtomicBoolean(true);
        doAnswer(inv -> {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return inv.callRealMethod();
        }).when(ps).setCurrentProjectRoot(any());
        Path chosen = tempDir.resolve("chosen-project");
        SwingTestUtils.awaitWorker(SwingTestUtils.callOnEdt(() -> header.switchProjectAsync(chosen)));
        assertFalse(calledOnEdt.get());
    }
}
