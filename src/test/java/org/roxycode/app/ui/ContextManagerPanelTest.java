package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.PromptService.ContextFileItem;
import org.roxycode.app.service.SettingsService;

class ContextManagerPanelTest {

    @Test
    void testProjectAndGlobalContextPanelsFolderManagementAndReadOnlyPreview() throws Exception {
        PromptService promptService = mock(PromptService.class);
        Path oldPath = Path.of("/tmp/docs/old.md");
        ContextFileItem oldItem = new ContextFileItem("/tmp/docs", "old.md", oldPath);

        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs"));
        when(promptService.listProjectContextFiles()).thenReturn(List.of(oldItem));
        when(promptService.readContextFile(oldPath)).thenReturn("# Old Content");

        ContextManagerPanel panel = new ContextManagerPanel(promptService, null);
        assertFalse(panel.getTextArea().isEditable(), "Preview text area must be read-only");

        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getListModel().getSize() == 1 && panel.getFolderListModel().getSize() == 1) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(1, panel.getFolderListModel().getSize());
        assertEquals("/tmp/docs", panel.getFolderListModel().getElementAt(0));
        assertEquals(1, panel.getListModel().getSize());

        panel.loadSelectedFileAsync(oldItem).get();
        for (int i = 0; i < 50 && panel.getTextArea().getText().isEmpty(); i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(10);
        }
        assertEquals("# Old Content", panel.getTextArea().getText());
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_MARKDOWN, panel.getTextArea().getSyntaxEditingStyle());

        // Add folder and refresh on project changed
        ContextFileItem new1 =
                new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java"));
        ContextFileItem new2 =
                new ContextFileItem("/tmp/new", "new2.json", Path.of("/tmp/new/new2.json"));
        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles()).thenReturn(List.of(new1, new2));

        panel.addFolderAsync("/tmp/new").get();
        verify(promptService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

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
        assertEquals("new1.java", panel.getListModel().getElementAt(0).fileName());
        assertEquals("new2.json", panel.getListModel().getElementAt(1).fileName());

        // GlobalContextPanel delegates to global context folder methods
        Path globalFile = Path.of("/global/rules.yaml");
        ContextFileItem globalItem = new ContextFileItem("/global", "rules.yaml", globalFile);
        when(promptService.getGlobalContextFolders()).thenReturn(List.of("/global"));
        when(promptService.listGlobalContextFiles()).thenReturn(List.of(globalItem));
        when(promptService.readContextFile(globalFile)).thenReturn("key: value");

        GlobalContextPanel globalPanel = new GlobalContextPanel(promptService, null);
        assertFalse(globalPanel.getTextArea().isEditable());
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (globalPanel.getListModel().getSize() == 1) {
                break;
            }
            Thread.sleep(20);
        }
        globalPanel.loadSelectedFileAsync(globalItem).get();
        for (int i = 0; i < 50 && globalPanel.getTextArea().getText().isEmpty(); i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(10);
        }
        assertEquals("key: value", globalPanel.getTextArea().getText());
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_YAML,
                globalPanel.getTextArea().getSyntaxEditingStyle());

        globalPanel.removeFolderAsync("/global").get();
        verify(promptService).setGlobalContextFolders(List.of());
    }

    @Test
    void testResolveSyntaxStyle() {
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                BaseContextFolderPanel.resolveSyntaxStyle("README.md"));
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_JAVA,
                BaseContextFolderPanel.resolveSyntaxStyle("App.java"));
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_JSON,
                BaseContextFolderPanel.resolveSyntaxStyle("config.json"));
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_XML,
                BaseContextFolderPanel.resolveSyntaxStyle("pom.xml"));
        assertEquals(
                SyntaxConstants.SYNTAX_STYLE_NONE,
                BaseContextFolderPanel.resolveSyntaxStyle("notes.txt"));
    }

    @Test
    void testAddFolderAndReadErrorSurfacesErrorMessage() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.getProjectContextFolders()).thenReturn(List.of());
        when(promptService.listProjectContextFiles()).thenReturn(List.of());
        doThrow(new IllegalStateException("No active project"))
                .when(promptService)
                .setProjectContextFolders(anyList());

        ContextManagerPanel panel = new ContextManagerPanel(promptService, null);
        List<String> errors = new ArrayList<>();
        panel.setErrorDialogPresenter(errors::add);

        try {
            panel.addFolderAsync("/tmp/folder").get();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 50 && errors.isEmpty(); i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(10);
        }
        assertFalse(errors.isEmpty(), "Error presenter should be invoked when addFolder fails");
        assertTrue(errors.get(0).contains("No active project"));
        assertTrue(panel.getLastErrorMessage().contains("No active project"));
    }

    @Test
    void testOnSettingsChangedUpdatesRSyntaxTextAreaTheme() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.getProjectContextFolders()).thenReturn(List.of());
        when(promptService.listProjectContextFiles()).thenReturn(List.of());
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
