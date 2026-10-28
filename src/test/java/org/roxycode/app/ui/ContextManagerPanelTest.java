package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.PromptService.ContextFileItem;

class ContextManagerPanelTest {

    private static void assertColumnIcons(ContextManagerPanel.ContextScopeColumn col) {
        assertEquals(Codicons.FOLDER,
                assertInstanceOf(FontIcon.class, col.getFoldersSectionLabel().getIcon()).getIkon());
        assertEquals(Codicons.FILE, assertInstanceOf(FontIcon.class, col.getFilesSectionLabel().getIcon()).getIkon());
        assertEquals(Codicons.ADD, assertInstanceOf(FontIcon.class, col.getAddFolderButton().getIcon()).getIkon());
        assertEquals(Codicons.TRASH, assertInstanceOf(FontIcon.class, col.getRemoveFolderButton().getIcon()).getIkon());
    }

    @Test
    void testTwoColumnGlobalAndProjectContextManagementWithoutPreview() {
        PromptService promptService = mock(PromptService.class);
        Path globalPath = Path.of("/global/rules.yaml");
        ContextFileItem globalItem = new ContextFileItem("/global", "rules.yaml", globalPath);
        Path projPath = Path.of("/tmp/docs/old.md");
        ContextFileItem projItem = new ContextFileItem("/tmp/docs", "old.md", projPath);

        when(promptService.getGlobalContextFolders()).thenReturn(List.of("/global"));
        when(promptService.listGlobalContextFiles()).thenReturn(List.of(globalItem));
        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs"));
        when(promptService.listProjectContextFiles()).thenReturn(List.of(projItem));

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        GridLayout layout = assertInstanceOf(GridLayout.class, panel.getLayout());
        assertEquals(1, layout.getRows());
        assertEquals(2, layout.getColumns());
        assertEquals(2, panel.getComponentCount());
        assertNull(SwingTestUtils.findComponent(panel, RSyntaxTextArea.class));

        var globalCol = panel.getGlobalColumn();
        var projCol = panel.getProjectColumn();
        SwingTestUtils.awaitCondition(
                () -> globalCol.getFolderListModel().getSize() == 1 && globalCol.getFileListModel().getSize() == 1
                        && projCol.getFolderListModel().getSize() == 1 && projCol.getFileListModel().getSize() == 1);

        assertEquals("/global", globalCol.getFolderListModel().getElementAt(0));
        assertEquals("rules.yaml", globalCol.getFileListModel().getElementAt(0).fileName());
        assertEquals("/tmp/docs", projCol.getFolderListModel().getElementAt(0));
        assertEquals("old.md", projCol.getFileListModel().getElementAt(0).fileName());

        JLabel cellLabel = assertInstanceOf(JLabel.class,
                SwingTestUtils.renderListCell(projCol.getFileList(), 0, false));
        assertEquals("old.md", cellLabel.getText());
        assertEquals(projPath.toString(), cellLabel.getToolTipText());
        assertEquals(Codicons.FILE, assertInstanceOf(FontIcon.class, cellLabel.getIcon()).getIkon());

        JLabel folderCellLabel = assertInstanceOf(JLabel.class,
                SwingTestUtils.renderListCell(globalCol.getFolderList(), 0, false));
        assertEquals("/global", folderCellLabel.getText());
        assertEquals("/global", folderCellLabel.getToolTipText());
        assertEquals(Codicons.FOLDER, assertInstanceOf(FontIcon.class, folderCellLabel.getIcon()).getIkon());

        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles())
                .thenReturn(List.of(new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java")),
                        new ContextFileItem("/tmp/new", "new2.json", Path.of("/tmp/new/new2.json"))));

        SwingTestUtils.awaitWorker(projCol.addFolderAsync("/tmp/new"));
        verify(promptService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));
        SwingTestUtils.awaitCondition(() -> projCol.getFileListModel().getSize() == 2);
        assertEquals("new1.java", projCol.getFileListModel().getElementAt(0).fileName());
        assertEquals("new2.json", projCol.getFileListModel().getElementAt(1).fileName());

        when(promptService.getGlobalContextFolders()).thenReturn(List.of("/global", "/global2"));
        SwingTestUtils.awaitWorker(globalCol.addFolderAsync("/global2"));
        verify(promptService).setGlobalContextFolders(List.of("/global", "/global2"));

        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        SwingTestUtils.awaitWorker(globalCol.removeFolderAsync("/global"));

        panel.onSettingsChanged(new SettingsChangedEvent(new AppSettings()));
        SwingTestUtils.awaitCondition(
                () -> globalCol.getFolderListModel().isEmpty() && globalCol.getFileListModel().isEmpty());
    }

    @Test
    void testVisualDistinctionIconsAccentsAndThemeUpdates() {
        ContextManagerPanel panel = new ContextManagerPanel(mock(PromptService.class));
        var globalCol = panel.getGlobalColumn();
        var projectCol = panel.getProjectColumn();

        assertNotEquals(globalCol.resolveAccentColor(), projectCol.resolveAccentColor());

        FontIcon globalHeaderIcon = assertInstanceOf(FontIcon.class, globalCol.getHeaderLabel().getIcon());
        FontIcon projectHeaderIcon = assertInstanceOf(FontIcon.class, projectCol.getHeaderLabel().getIcon());
        assertEquals(Codicons.GLOBE, globalHeaderIcon.getIkon());
        assertEquals(Codicons.FOLDER_OPENED, projectHeaderIcon.getIkon());
        assertEquals(globalCol.resolveAccentColor(), globalHeaderIcon.getIconColor());
        assertEquals(projectCol.resolveAccentColor(), projectHeaderIcon.getIconColor());
        assertEquals(ContextManagerPanel.GLOBAL_SUBTITLE, globalCol.getSubtitleLabel().getText());
        assertEquals(ContextManagerPanel.PROJECT_SUBTITLE, projectCol.getSubtitleLabel().getText());

        String globalBannerStyle = (String) globalCol.getHeaderBanner().getClientProperty(FlatClientProperties.STYLE);
        String projectBannerStyle = (String) projectCol.getHeaderBanner().getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(globalBannerStyle);
        assertNotNull(projectBannerStyle);
        assertNotEquals(globalBannerStyle, projectBannerStyle);
        assertFalse(globalBannerStyle.contains("fade("));
        assertFalse(projectBannerStyle.contains("fade("));
        assertEquals(255, globalCol.getHeaderBanner().getBackground().getAlpha());
        assertEquals(255, projectCol.getHeaderBanner().getBackground().getAlpha());

        assertColumnIcons(globalCol);
        assertColumnIcons(projectCol);

        SwingTestUtils.withDarkTheme(panel,
                () -> assertNotEquals(globalCol.resolveAccentColor(), projectCol.resolveAccentColor()));
        assertNotEquals(globalCol.resolveAccentColor(), projectCol.resolveAccentColor());
    }

    @Test
    void testAddFolderErrorSurfacesErrorMessage() {
        PromptService promptService = mock(PromptService.class);
        doThrow(new IllegalStateException("No active project")).when(promptService).setProjectContextFolders(anyList());

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        List<String> errors = new ArrayList<>();
        panel.setErrorDialogPresenter(errors::add);

        try {
            SwingTestUtils.awaitWorker(panel.getProjectColumn().addFolderAsync("/tmp/folder"));
        } catch (Exception ignored) {
        }
        SwingTestUtils.awaitCondition(() -> !errors.isEmpty());
        assertTrue(errors.get(0).contains("No active project"));
        assertTrue(panel.getLastErrorMessage().contains("No active project"));
    }
}
