package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
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

    @Test
    void testTwoColumnGlobalAndProjectContextManagementWithoutPreview() throws Exception {
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
        assertNull(
                SwingTestUtils.findComponent(panel, RSyntaxTextArea.class),
                "ContextManagerPanel should not contain a file preview RSyntaxTextArea");

        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getGlobalFolderListModel().getSize() == 1
                    && panel.getGlobalFileListModel().getSize() == 1
                    && panel.getProjectFolderListModel().getSize() == 1
                    && panel.getProjectFileListModel().getSize() == 1) {
                break;
            }
            Thread.sleep(20);
        }

        assertEquals(1, panel.getGlobalFolderListModel().getSize());
        assertEquals("/global", panel.getGlobalFolderListModel().getElementAt(0));
        assertEquals(1, panel.getGlobalFileListModel().getSize());
        assertEquals("rules.yaml", panel.getGlobalFileListModel().getElementAt(0).fileName());

        assertEquals(1, panel.getProjectFolderListModel().getSize());
        assertEquals("/tmp/docs", panel.getProjectFolderListModel().getElementAt(0));
        assertEquals(1, panel.getProjectFileListModel().getSize());
        assertEquals("old.md", panel.getProjectFileListModel().getElementAt(0).fileName());

        // Verify file cell renderer displays fileName, full path tooltip, and FILE icon
        Component rendered =
                panel.getProjectColumn()
                        .getFileList()
                        .getCellRenderer()
                        .getListCellRendererComponent(
                                panel.getProjectColumn().getFileList(), projItem, 0, false, false);
        JLabel cellLabel = assertInstanceOf(JLabel.class, rendered);
        assertEquals("old.md", cellLabel.getText());
        assertEquals(projPath.toString(), cellLabel.getToolTipText());
        FontIcon fileIcon = assertInstanceOf(FontIcon.class, cellLabel.getIcon());
        assertEquals(Codicons.FILE, fileIcon.getIkon());

        // Verify folder cell renderer displays folder path, tooltip, and FOLDER icon
        Component folderRendered =
                panel.getGlobalColumn()
                        .getFolderList()
                        .getCellRenderer()
                        .getListCellRendererComponent(
                                panel.getGlobalColumn().getFolderList(),
                                "/global",
                                0,
                                false,
                                false);
        JLabel folderCellLabel = assertInstanceOf(JLabel.class, folderRendered);
        assertEquals("/global", folderCellLabel.getText());
        assertEquals("/global", folderCellLabel.getToolTipText());
        FontIcon folderIcon = assertInstanceOf(FontIcon.class, folderCellLabel.getIcon());
        assertEquals(Codicons.FOLDER, folderIcon.getIkon());

        // Add project folder and refresh on ProjectChangedEvent
        ContextFileItem new1 =
                new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java"));
        ContextFileItem new2 =
                new ContextFileItem("/tmp/new", "new2.json", Path.of("/tmp/new/new2.json"));
        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles()).thenReturn(List.of(new1, new2));

        panel.addProjectFolderAsync("/tmp/new").get();
        verify(promptService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getProjectFileListModel().getSize() == 2) {
                break;
            }
            Thread.sleep(20);
        }

        assertEquals(2, panel.getProjectFileListModel().getSize());
        assertEquals("new1.java", panel.getProjectFileListModel().getElementAt(0).fileName());
        assertEquals("new2.json", panel.getProjectFileListModel().getElementAt(1).fileName());

        // Add & remove global folder and refresh on SettingsChangedEvent
        when(promptService.getGlobalContextFolders()).thenReturn(List.of("/global", "/global2"));
        panel.addGlobalFolderAsync("/global2").get();
        verify(promptService).setGlobalContextFolders(List.of("/global", "/global2"));

        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        panel.removeGlobalFolderAsync("/global").get();

        panel.onSettingsChanged(new SettingsChangedEvent(new AppSettings()));
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getGlobalFolderListModel().isEmpty()
                    && panel.getGlobalFileListModel().isEmpty()) {
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(panel.getGlobalFolderListModel().isEmpty());
        assertTrue(panel.getGlobalFileListModel().isEmpty());
    }

    @Test
    void testVisualDistinctionIconsAccentsAndThemeUpdates() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        when(promptService.getProjectContextFolders()).thenReturn(List.of());
        when(promptService.listProjectContextFiles()).thenReturn(List.of());

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        ContextManagerPanel.ContextScopeColumn globalCol = panel.getGlobalColumn();
        ContextManagerPanel.ContextScopeColumn projectCol = panel.getProjectColumn();

        // Distinct accent colors for Global (Blue) vs Project (Green)
        assertNotEquals(
                globalCol.resolveAccentColor(),
                projectCol.resolveAccentColor(),
                "Global and Project columns must have visually distinct accent colors");

        // Header icons & subtitles
        FontIcon globalHeaderIcon =
                assertInstanceOf(FontIcon.class, globalCol.getHeaderLabel().getIcon());
        FontIcon projectHeaderIcon =
                assertInstanceOf(FontIcon.class, projectCol.getHeaderLabel().getIcon());
        assertEquals(Codicons.GLOBE, globalHeaderIcon.getIkon());
        assertEquals(Codicons.FOLDER_OPENED, projectHeaderIcon.getIkon());
        assertEquals(globalCol.resolveAccentColor(), globalHeaderIcon.getIconColor());
        assertEquals(projectCol.resolveAccentColor(), projectHeaderIcon.getIconColor());

        assertEquals(ContextManagerPanel.GLOBAL_SUBTITLE, globalCol.getSubtitleLabel().getText());
        assertEquals(ContextManagerPanel.PROJECT_SUBTITLE, projectCol.getSubtitleLabel().getText());

        // Header banner tinted styles
        String globalBannerStyle =
                (String) globalCol.getHeaderBanner().getClientProperty(FlatClientProperties.STYLE);
        String projectBannerStyle =
                (String) projectCol.getHeaderBanner().getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(globalBannerStyle);
        assertNotNull(projectBannerStyle);
        assertNotEquals(globalBannerStyle, projectBannerStyle);

        // Section labels and action button icons
        assertEquals(
                Codicons.FOLDER,
                assertInstanceOf(FontIcon.class, globalCol.getFoldersSectionLabel().getIcon())
                        .getIkon());
        assertEquals(
                Codicons.FILE,
                assertInstanceOf(FontIcon.class, globalCol.getFilesSectionLabel().getIcon())
                        .getIkon());
        assertEquals(
                Codicons.ADD,
                assertInstanceOf(FontIcon.class, globalCol.getAddFolderButton().getIcon())
                        .getIkon());
        assertEquals(
                Codicons.TRASH,
                assertInstanceOf(FontIcon.class, globalCol.getRemoveFolderButton().getIcon())
                        .getIkon());

        assertEquals(
                Codicons.FOLDER,
                assertInstanceOf(FontIcon.class, projectCol.getFoldersSectionLabel().getIcon())
                        .getIkon());
        assertEquals(
                Codicons.FILE,
                assertInstanceOf(FontIcon.class, projectCol.getFilesSectionLabel().getIcon())
                        .getIkon());
        assertEquals(
                Codicons.ADD,
                assertInstanceOf(FontIcon.class, projectCol.getAddFolderButton().getIcon())
                        .getIkon());
        assertEquals(
                Codicons.TRASH,
                assertInstanceOf(FontIcon.class, projectCol.getRemoveFolderButton().getIcon())
                        .getIkon());

        // Verify FlatLaf theme switching and updateUI() without style parse errors
        FlatDarkLaf.setup();
        SwingUtilities.updateComponentTreeUI(panel);
        assertNotEquals(globalCol.resolveAccentColor(), projectCol.resolveAccentColor());

        FlatLightLaf.setup();
        SwingUtilities.updateComponentTreeUI(panel);
        assertNotEquals(globalCol.resolveAccentColor(), projectCol.resolveAccentColor());
    }

    @Test
    void testAddFolderErrorSurfacesErrorMessage() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        when(promptService.getProjectContextFolders()).thenReturn(List.of());
        when(promptService.listProjectContextFiles()).thenReturn(List.of());
        doThrow(new IllegalStateException("No active project"))
                .when(promptService)
                .setProjectContextFolders(anyList());

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        List<String> errors = new ArrayList<>();
        panel.setErrorDialogPresenter(errors::add);

        try {
            panel.addProjectFolderAsync("/tmp/folder").get();
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
}
