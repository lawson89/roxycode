package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        AtomicReference<String> navigatedCard = new AtomicReference<>();
        header.setOnNavigateToCard(navigatedCard::set);

        assertEquals(Cursor.HAND_CURSOR, header.projectSwitcherButton.getCursor().getType());
        assertEquals(Cursor.HAND_CURSOR, header.modelLabel.getCursor().getType());
        assertEquals(Cursor.HAND_CURSOR, header.groundingLabel.getCursor().getType());

        // Without active project / API key, clicking modelLabel navigates to SETTINGS
        SwingTestUtils.runOnEdt(() -> {
            MouseEvent click = new MouseEvent(header.modelLabel, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 4, 4, 1, false);
            for (MouseListener listener : header.modelLabel.getMouseListeners()) {
                listener.mouseClicked(click);
            }
        });
        assertEquals(MainFrame.SETTINGS_VIEW, navigatedCard.get());

        assertTrue(header.noProjectNoticeLabel.isVisible());
        assertEquals(HeaderPanel.NO_PROJECT_NOTICE_TEXT, header.noProjectNoticeLabel.getText());
        assertEquals(HeaderPanel.OPEN_PROJECT_TEXT, header.projectSwitcherButton.getText());
        when(gitService.getCurrentBranch()).thenReturn("No active project");
        SwingTestUtils.awaitFuture(header.refreshGitBranch());
        assertFalse(header.branchLabel.isVisible());

        when(gitService.getCurrentBranch()).thenReturn("feature/async-branch");
        SwingTestUtils.awaitFuture(header.refreshGitBranch());
        assertEquals("feature/async-branch", header.branchLabel.getText());
        assertTrue(header.branchLabel.isVisible());

        Path newRoot = tempDir.resolve("updated-proj");
        ps.setCurrentProjectRoot(newRoot);
        AppSettings updatedSettings = ss.getSettings();
        updatedSettings.setGeminiApiKey("test-api-key");
        updatedSettings.setAgentProfile("NORMAL");

        Thread bg = new Thread(() -> {
            header.onProjectChanged(new ProjectChangedEvent(newRoot));
            header.onSettingsChanged(new SettingsChangedEvent(updatedSettings));
        });
        bg.start();
        bg.join(5000);
        SwingTestUtils.flushEdt();

        assertEquals("updated-proj", header.projectSwitcherButton.getText());
        assertEquals(newRoot.toAbsolutePath().toString(), header.projectSwitcherButton.getToolTipText());
        assertFalse(header.noProjectNoticeLabel.isVisible());
        assertEquals("Normal • Argon → Hydrogen", header.modelLabel.getText());
        assertEquals("Active Agent Profile: Normal (Argon → Hydrogen) — Click to configure Agent Profile",
                header.modelLabel.getToolTipText());

        // With active project and credentials, clicking modelLabel navigates to PROJECT_MODEL_VIEW, and groundingLabel
        // to SETTINGS
        SwingTestUtils.runOnEdt(() -> {
            MouseEvent modelClick = new MouseEvent(header.modelLabel, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 4, 4, 1, false);
            for (MouseListener listener : header.modelLabel.getMouseListeners()) {
                listener.mouseClicked(modelClick);
            }
        });
        assertEquals(MainFrame.PROJECT_MODEL_VIEW, navigatedCard.get());

        SwingTestUtils.runOnEdt(() -> {
            MouseEvent groundingClick = new MouseEvent(header.groundingLabel, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 4, 4, 1, false);
            for (MouseListener listener : header.groundingLabel.getMouseListeners()) {
                listener.mouseClicked(groundingClick);
            }
        });
        assertEquals(MainFrame.SETTINGS_VIEW, navigatedCard.get());

        updatedSettings.setAgentProfile("SPEED");
        SwingTestUtils.runOnEdt(() -> header.onSettingsChanged(new SettingsChangedEvent(updatedSettings)));
        assertEquals("Speed • Pure Hydrogen", header.modelLabel.getText());
        assertEquals("Active Agent Profile: Speed (Pure Hydrogen) — Click to configure Agent Profile",
                header.modelLabel.getToolTipText());

        ps.setProjectAgentProfile("MAX");
        SwingTestUtils.runOnEdt(() -> header.onSettingsChanged(new SettingsChangedEvent(updatedSettings)));
        assertEquals("Max • Carbon → Hydrogen", header.modelLabel.getText());
        assertEquals("Active Agent Profile: Max (Carbon → Hydrogen) — Click to configure Agent Profile",
                header.modelLabel.getToolTipText());

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
