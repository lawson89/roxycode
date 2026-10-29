package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.PromptService.ContextFileItem;

class ContextManagerPanelTest {

    @Test
    void testGlobalAndProjectContextManagementAndSelectionClearing() {
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
        var globalCol = panel.globalColumn;
        var projCol = panel.projectColumn;
        SwingTestUtils
                .awaitCondition(() -> globalCol.folderListModel.getSize() == 1 && globalCol.fileListModel.getSize() == 1
                        && projCol.folderListModel.getSize() == 1 && projCol.fileListModel.getSize() == 1);

        assertEquals("/global", globalCol.folderListModel.getElementAt(0));
        assertEquals("rules.yaml", globalCol.fileListModel.getElementAt(0).fileName());
        assertEquals("/tmp/docs", projCol.folderListModel.getElementAt(0));
        assertEquals("old.md", projCol.fileListModel.getElementAt(0).fileName());

        assertFalse(globalCol.btnRemoveFolder.isEnabled());
        SwingTestUtils.runOnEdt(() -> globalCol.folderList.setSelectedIndex(0));
        assertTrue(globalCol.btnRemoveFolder.isEnabled());
        SwingTestUtils.runOnEdt(globalCol::clearSelection);
        assertEquals(-1, globalCol.folderList.getSelectedIndex());
        assertFalse(globalCol.btnRemoveFolder.isEnabled());

        JLabel cellLabel = assertInstanceOf(JLabel.class, SwingTestUtils.renderListCell(projCol.fileList, 0, false));
        assertEquals("old.md", cellLabel.getText());
        assertEquals(projPath.toString(), cellLabel.getToolTipText());

        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles())
                .thenReturn(List.of(new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java")),
                        new ContextFileItem("/tmp/new", "new2.json", Path.of("/tmp/new/new2.json"))));

        SwingTestUtils.awaitWorker(projCol.addFolderAsync("/tmp/new"));
        verify(promptService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));
        SwingTestUtils.awaitCondition(() -> projCol.fileListModel.getSize() == 2);
        assertEquals("new1.java", projCol.fileListModel.getElementAt(0).fileName());
        assertEquals("new2.json", projCol.fileListModel.getElementAt(1).fileName());

        when(promptService.getGlobalContextFolders()).thenReturn(List.of("/global", "/global2"));
        SwingTestUtils.awaitWorker(globalCol.addFolderAsync("/global2"));
        verify(promptService).setGlobalContextFolders(List.of("/global", "/global2"));

        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        SwingTestUtils.awaitWorker(globalCol.removeFolderAsync("/global"));

        panel.onSettingsChanged(new SettingsChangedEvent(new AppSettings()));
        SwingTestUtils.awaitCondition(() -> globalCol.folderListModel.isEmpty() && globalCol.fileListModel.isEmpty());
    }

    @Test
    void testAddFolderErrorSurfacesErrorMessage() {
        PromptService promptService = mock(PromptService.class);
        doThrow(new IllegalStateException("No active project")).when(promptService).setProjectContextFolders(anyList());

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        List<String> errors = new ArrayList<>();
        panel.setErrorDialogPresenter(errors::add);

        try {
            SwingTestUtils.awaitWorker(panel.projectColumn.addFolderAsync("/tmp/folder"));
        } catch (Exception ignored) {
        }
        SwingTestUtils.awaitCondition(() -> !errors.isEmpty());
        assertTrue(errors.get(0).contains("No active project"));
        assertTrue(panel.lastErrorMessage.contains("No active project"));
    }
}
