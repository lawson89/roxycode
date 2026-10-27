package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Cursor;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class HeaderPanelTest {
    @TempDir Path tempDir;

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

    private HeaderPanel createHeader() {
        SettingsService ss = createSettingsService();
        return new HeaderPanel(new ProjectService(ss, null), createGitService(), ss);
    }

    @Test
    void testHeaderPanelInstantiation() {
        SettingsService ss = createSettingsService();
        ProjectService ps = new ProjectService(ss, null);
        HeaderPanel header = new HeaderPanel(ps, createGitService(), ss);

        Path newRoot = tempDir.resolve("updated-proj");
        ps.setCurrentProjectRoot(newRoot);
        SwingTestUtils.runOnEdt(() -> header.onProjectChanged(new ProjectChangedEvent(newRoot)));

        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiModel("gemini-4-argon");
        SwingTestUtils.runOnEdt(
                () -> header.onSettingsChanged(new SettingsChangedEvent(updatedSettings)));

        JLabel projectLabel =
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "updated-proj".equals(c.getText()));
        assertNotNull(projectLabel);
        assertEquals(
                Codicons.REPO, assertInstanceOf(FontIcon.class, projectLabel.getIcon()).getIkon());

        JLabel modelLabel =
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "gemini-4-argon".equals(c.getText()));
        assertNotNull(modelLabel);
        assertEquals(
                Codicons.HUBOT, assertInstanceOf(FontIcon.class, modelLabel.getIcon()).getIkon());
        String modelStyle = (String) modelLabel.getClientProperty(FlatClientProperties.STYLE);
        assertTrue(
                modelStyle != null
                        && modelStyle.contains("arc: 10")
                        && modelStyle.contains("border: 3,8,3,8"));
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

        Thread backgroundThread =
                new Thread(
                        () -> {
                            header.onProjectChanged(new ProjectChangedEvent(newRoot));
                            header.onSettingsChanged(new SettingsChangedEvent(updatedSettings));
                        });
        backgroundThread.start();
        backgroundThread.join(5000);
        SwingTestUtils.flushEdt();

        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "off-edt-proj".equals(c.getText())));
        assertNotNull(
                SwingTestUtils.findComponent(
                        header, JLabel.class, c -> "gemini-off-edt".equals(c.getText())));
    }

    @Test
    void testUpdateBranchLabelRunsAsyncOffEdt() {
        SettingsService ss = createSettingsService();
        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("feature/async-branch");

        HeaderPanel header = new HeaderPanel(new ProjectService(ss, null), gitService, ss);
        SwingTestUtils.awaitWorker(header.updateBranchLabel());
        SwingTestUtils.awaitCondition(
                () -> "feature/async-branch".equals(header.getBranchLabel().getText()));
        assertTrue(header.getBranchLabel().isVisible());
        assertEquals(
                Codicons.SOURCE_CONTROL,
                assertInstanceOf(FontIcon.class, header.getBranchLabel().getIcon()).getIkon());
        String branchStyle =
                (String) header.getBranchLabel().getClientProperty(FlatClientProperties.STYLE);
        assertTrue(
                branchStyle != null
                        && branchStyle.contains("arc: 10")
                        && branchStyle.contains("border: 3,8,3,8"));

        header.updateUI();
        assertNotNull(header.getBranchLabel().getIcon());
    }

    @Test
    void testModeTogglesAndWorkflowIndicatorsAreRemovedFromHeader() {
        HeaderPanel header = createHeader();
        assertNull(SwingTestUtils.findComponent(header, JToggleButton.class));
        assertNull(SwingTestUtils.findComponent(header, WorkflowPhaseIndicator.class));
    }

    @Test
    void testChangeProjectButtonIsPresentAndStyled() {
        HeaderPanel header = createHeader();
        JButton openButton =
                SwingTestUtils.findComponent(
                        header, JButton.class, c -> "Open Project".equals(c.getToolTipText()));
        assertSame(header.getChangeProjectButton(), openButton);
        assertEquals(HeaderPanel.CHANGE_PROJECT_TEXT, openButton.getText());
        assertEquals(Cursor.HAND_CURSOR, openButton.getCursor().getType());
        assertEquals(
                Codicons.FOLDER_OPENED,
                assertInstanceOf(FontIcon.class, openButton.getIcon()).getIkon());
        String style = (String) openButton.getClientProperty(FlatClientProperties.STYLE);
        assertTrue(
                style != null && style.contains("arc: 10") && style.contains("hoverBackground:"));
    }

    @Test
    void testThemeToggleButtonSwitchesAndSavesTheme() {
        SettingsService ss = spy(createSettingsService());
        HeaderPanel header = new HeaderPanel(new ProjectService(ss, null), createGitService(), ss);

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
        assertEquals(3, openButton.getParent().getComponentCount());
    }

    @Test
    void testSwitchProjectAsyncRunsOffEdt() {
        SettingsService ss = createSettingsService();
        ProjectService ps = spy(new ProjectService(ss, null));
        HeaderPanel header = new HeaderPanel(ps, createGitService(), ss);
        AtomicBoolean calledOnEdt = new AtomicBoolean(true);
        doAnswer(
                        inv -> {
                            calledOnEdt.set(SwingUtilities.isEventDispatchThread());
                            return inv.callRealMethod();
                        })
                .when(ps)
                .setCurrentProjectRoot(any());
        Path chosen = tempDir.resolve("chosen-project");
        SwingTestUtils.awaitWorker(
                SwingTestUtils.callOnEdt(() -> header.switchProjectAsync(chosen)));
        assertFalse(calledOnEdt.get());
    }
}
