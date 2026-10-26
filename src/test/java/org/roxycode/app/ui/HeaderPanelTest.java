package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Container;
import java.awt.Insets;
import java.nio.file.Path;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class HeaderPanelTest {
    @TempDir Path tempDir;

    private SettingsService createSettingsService() {
        SettingsService settingsService = new SettingsService(null, null);
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        return settingsService;
    }

    private ProjectService createProjectService(SettingsService ss) {
        return new ProjectService(ss, null);
    }

    private GitService createGitService(ProjectService ps) {
        GitService gitService = mock(GitService.class);
        lenient().when(gitService.getCurrentBranch()).thenReturn("Not a git repository");
        return gitService;
    }

    private HeaderPanel createHeader() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        return new HeaderPanel(ps, createGitService(ps), ss);
    }

    @Test
    void testHeaderPanelInstantiation() throws Exception {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);

        Path newRoot = tempDir.resolve("updated-proj");
        ps.setCurrentProjectRoot(newRoot);
        SwingUtilities.invokeAndWait(
                () -> header.onProjectChanged(new ProjectChangedEvent(newRoot)));

        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiModel("gemini-4-argon");
        SwingUtilities.invokeAndWait(
                () -> header.onSettingsChanged(new SettingsChangedEvent(updatedSettings)));

        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "updated-proj".equals(c.getText())));
        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "gemini-4-argon".equals(c.getText())));
    }

    @Test
    void testOnProjectChangedAndOnSettingsChangedOffEdtDispatchToEdt() throws Exception {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);

        Path newRoot = tempDir.resolve("off-edt-proj");
        ps.setCurrentProjectRoot(newRoot);
        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiModel("gemini-off-edt");

        Thread backgroundThread =
                new Thread(
                        () -> {
                            header.onProjectChanged(new ProjectChangedEvent(newRoot));
                            header.onSettingsChanged(new SettingsChangedEvent(updatedSettings));
                        });
        backgroundThread.start();
        backgroundThread.join(5000);
        SwingUtilities.invokeAndWait(() -> {});

        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "off-edt-proj".equals(c.getText())));
        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "gemini-off-edt".equals(c.getText())));
    }

    @Test
    void testUpdateBranchLabelRunsAsyncOffEdt() throws Exception {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("feature/async-branch");

        HeaderPanel header = new HeaderPanel(ps, gitService, ss);
        header.updateBranchLabel().get();

        for (int i = 0;
                i < 50 && !"feature/async-branch".equals(header.getBranchLabel().getText());
                i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(20);
        }

        assertEquals("feature/async-branch", header.getBranchLabel().getText());
        assertTrue(header.getBranchLabel().isVisible());
    }

    @Test
    void testModeTogglesAndWorkflowIndicatorsAreRemovedFromHeader() {
        HeaderPanel header = createHeader();
        assertNull(SwingTestUtils.findComponent(header, JToggleButton.class));
        assertNull(SwingTestUtils.findComponent(header, WorkflowPhaseIndicator.class));
    }

    @Test
    void testOpenButtonIsPresent() {
        HeaderPanel header = createHeader();
        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JButton.class, c -> "Open Project".equals(c.getToolTipText())));
    }

    @Test
    void testThemeToggleButtonSwitchesAndSavesTheme() {
        SettingsService ss = spy(createSettingsService());
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss);

        JButton themeButton =
                SwingTestUtils.findComponent(
                        header,
                        JButton.class,
                        c -> "Toggle Light/Dark Theme".equals(c.getToolTipText()));
        assertNotNull(themeButton);
        assertEquals("FlatLaf Light", ss.getSettings().getTheme());

        themeButton.doClick();
        assertEquals("FlatLaf Dark", ss.getSettings().getTheme());
        verify(ss, times(1)).saveSettings();

        themeButton.doClick();
        assertEquals("FlatLaf Light", ss.getSettings().getTheme());
        verify(ss, times(2)).saveSettings();

        for (String darkTheme : new String[] {"Darcula", "macOS Dark"}) {
            ss.getSettings().setTheme(darkTheme);
            SettingsPanel.applyTheme(darkTheme);
            themeButton.doClick();
            assertEquals("FlatLaf Light", ss.getSettings().getTheme());
        }
    }

    @Test
    void testHeaderLayoutInsetsAndInlineLeftSection() {
        HeaderPanel header = createHeader();
        MigLayout layout = (MigLayout) header.getLayout();
        assertTrue(layout.getLayoutConstraints().toString().contains("insets 2 15 2 15"));
        header.updateUI();
        assertEquals(new Insets(0, 0, 1, 0), header.getBorder().getBorderInsets(header));

        JButton openButton =
                SwingTestUtils.findComponent(
                        header, JButton.class, c -> "Open Project".equals(c.getToolTipText()));
        assertNotNull(openButton);
        Container leftPanel = openButton.getParent();
        assertEquals(3, leftPanel.getComponentCount());
    }
}
