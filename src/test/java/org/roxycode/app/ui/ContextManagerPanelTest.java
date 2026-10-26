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
import java.util.function.BooleanSupplier;
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

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    private static void assertColumnIcons(ContextManagerPanel.ContextScopeColumn col) {
        assertEquals(
                Codicons.FOLDER,
                assertInstanceOf(FontIcon.class, col.getFoldersSectionLabel().getIcon()).getIkon());
        assertEquals(
                Codicons.FILE,
                assertInstanceOf(FontIcon.class, col.getFilesSectionLabel().getIcon()).getIkon());
        assertEquals(
                Codicons.ADD,
                assertInstanceOf(FontIcon.class, col.getAddFolderButton().getIcon()).getIkon());
        assertEquals(
                Codicons.TRASH,
                assertInstanceOf(FontIcon.class, col.getRemoveFolderButton().getIcon()).getIkon());
    }

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
        assertNull(SwingTestUtils.findComponent(panel, RSyntaxTextArea.class));

        awaitCondition(
                () ->
                        panel.getGlobalFolderListModel().getSize() == 1
                                && panel.getGlobalFileListModel().getSize() == 1
                                && panel.getProjectFolderListModel().getSize() == 1
                                && panel.getProjectFileListModel().getSize() == 1);

        assertEquals("/global", panel.getGlobalFolderListModel().getElementAt(0));
        assertEquals("rules.yaml", panel.getGlobalFileListModel().getElementAt(0).fileName());
        assertEquals("/tmp/docs", panel.getProjectFolderListModel().getElementAt(0));
        assertEquals("old.md", panel.getProjectFileListModel().getElementAt(0).fileName());

        Component rendered =
                panel.getProjectColumn()
                        .getFileList()
                        .getCellRenderer()
                        .getListCellRendererComponent(
                                panel.getProjectColumn().getFileList(), projItem, 0, false, false);
        JLabel cellLabel = assertInstanceOf(JLabel.class, rendered);
        assertEquals("old.md", cellLabel.getText());
        assertEquals(projPath.toString(), cellLabel.getToolTipText());
        assertEquals(
                Codicons.FILE, assertInstanceOf(FontIcon.class, cellLabel.getIcon()).getIkon());

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
        assertEquals(
                Codicons.FOLDER,
                assertInstanceOf(FontIcon.class, folderCellLabel.getIcon()).getIkon());

        ContextFileItem new1 =
                new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java"));
        ContextFileItem new2 =
                new ContextFileItem("/tmp/new", "new2.json", Path.of("/tmp/new/new2.json"));
        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles()).thenReturn(List.of(new1, new2));

        panel.addProjectFolderAsync("/tmp/new").get();
        verify(promptService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));
        awaitCondition(() -> panel.getProjectFileListModel().getSize() == 2);

        assertEquals("new1.java", panel.getProjectFileListModel().getElementAt(0).fileName());
        assertEquals("new2.json", panel.getProjectFileListModel().getElementAt(1).fileName());

        when(promptService.getGlobalContextFolders()).thenReturn(List.of("/global", "/global2"));
        panel.addGlobalFolderAsync("/global2").get();
        verify(promptService).setGlobalContextFolders(List.of("/global", "/global2"));

        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        panel.removeGlobalFolderAsync("/global").get();

        panel.onSettingsChanged(new SettingsChangedEvent(new AppSettings()));
        awaitCondition(
                () ->
                        panel.getGlobalFolderListModel().isEmpty()
                                && panel.getGlobalFileListModel().isEmpty());
        assertTrue(panel.getGlobalFolderListModel().isEmpty());
        assertTrue(panel.getGlobalFileListModel().isEmpty());
    }

    @Test
    void testVisualDistinctionIconsAccentsAndThemeUpdates() throws Exception {
        PromptService promptService = mock(PromptService.class);
        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        ContextManagerPanel.ContextScopeColumn globalCol = panel.getGlobalColumn();
        ContextManagerPanel.ContextScopeColumn projectCol = panel.getProjectColumn();

        assertNotEquals(globalCol.resolveAccentColor(), projectCol.resolveAccentColor());

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

        String globalBannerStyle =
                (String) globalCol.getHeaderBanner().getClientProperty(FlatClientProperties.STYLE);
        String projectBannerStyle =
                (String) projectCol.getHeaderBanner().getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(globalBannerStyle);
        assertNotNull(projectBannerStyle);
        assertNotEquals(globalBannerStyle, projectBannerStyle);

        assertColumnIcons(globalCol);
        assertColumnIcons(projectCol);

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
        awaitCondition(() -> !errors.isEmpty());
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("No active project"));
        assertTrue(panel.getLastErrorMessage().contains("No active project"));
    }
}
