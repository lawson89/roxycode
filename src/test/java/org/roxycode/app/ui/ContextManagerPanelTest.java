package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;

class ContextManagerPanelTest {

    @Test
    void testOnProjectChangedClearsEditorAndRefreshesFileList() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.listContextFiles()).thenReturn(List.of("old.md"));
        when(promptService.readContextFile("old.md")).thenReturn("# Old Content");

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getListModel().getSize() == 1) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(1, panel.getListModel().getSize());

        panel.loadSelectedFileAsync("old.md").get();
        SwingUtilities.invokeAndWait(() -> {});
        panel.getTextArea().setText("# Old Content");

        when(promptService.listContextFiles()).thenReturn(List.of("new1.md", "new2.md"));
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));

        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getListModel().getSize() == 2) {
                break;
            }
            Thread.sleep(20);
        }

        assertEquals("", panel.getTextArea().getText());
        assertEquals(2, panel.getListModel().getSize());
        assertEquals("new1.md", panel.getListModel().getElementAt(0));
        assertEquals("new2.md", panel.getListModel().getElementAt(1));
    }

    @Test
    void testNormalizeRuleFilenameTrimsInput() {
        assertNull(ContextManagerPanel.normalizeRuleFilename(null));
        assertEquals("", ContextManagerPanel.normalizeRuleFilename("   "));
        assertEquals("foo.md", ContextManagerPanel.normalizeRuleFilename(" foo "));
        assertEquals("rules.md", ContextManagerPanel.normalizeRuleFilename(" rules.md "));
    }

    @Test
    void testSaveAndCreateErrorSurfacesErrorMessage() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.listContextFiles()).thenReturn(List.of());
        doThrow(new IllegalArgumentException("Invalid context filename: ../evil.md"))
                .when(promptService)
                .saveContextFile(eq("../evil.md"), anyString());

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        List<String> errors = new ArrayList<>();
        panel.setErrorDialogPresenter(errors::add);

        try {
            panel.createNewFileAsync("../evil.md").get();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 50 && errors.isEmpty(); i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(10);
        }
        assertFalse(errors.isEmpty(), "Error presenter should be invoked when create fails");
        assertTrue(errors.get(0).contains("Invalid context filename"));

        errors.clear();
        try {
            panel.saveFileAsync("../evil.md", "content").get();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 50 && errors.isEmpty(); i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(10);
        }
        assertFalse(errors.isEmpty(), "Error presenter should be invoked when save fails");
        assertTrue(panel.getLastErrorMessage().contains("Invalid context filename"));
    }

    @Test
    void testOnSettingsChangedUpdatesRSyntaxTextAreaTheme() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.listContextFiles()).thenReturn(List.of());
        SettingsService settingsService = mock(SettingsService.class);
        AppSettings settings = new AppSettings();
        settings.setTheme("FlatLaf Light");
        when(settingsService.getSettings()).thenReturn(settings);

        ContextManagerPanel panel = new ContextManagerPanel(promptService, settingsService);
        Color lightBg = panel.getTextArea().getBackground();

        settings.setTheme("Darcula");
        panel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingUtilities.invokeAndWait(() -> {});
        Color darkBg = panel.getTextArea().getBackground();

        assertNotEquals(
                lightBg,
                darkBg,
                "RSyntaxTextArea background should switch to dark theme on Darcula");

        settings.setTheme("FlatLaf Light");
        panel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(
                lightBg,
                panel.getTextArea().getBackground(),
                "RSyntaxTextArea background should return to light theme");
    }
}
