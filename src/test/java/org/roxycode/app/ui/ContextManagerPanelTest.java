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
    void testGlobalAndProjectContextManagementAndErrorHandling() {
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
        ContextManagerPanel.ContextScopeColumn globalCol = panel.globalColumn;
        ContextManagerPanel.ContextScopeColumn projCol = panel.projectColumn;
        SwingTestUtils.awaitFuture(globalCol.lastRefreshFuture);
        SwingTestUtils.awaitFuture(projCol.lastRefreshFuture);

        assertEquals("/global", globalCol.folderListModel.getElementAt(0));
        assertEquals("rules.yaml", globalCol.fileListModel.getElementAt(0).fileName());
        assertEquals("/tmp/docs", projCol.folderListModel.getElementAt(0));
        assertEquals("old.md", projCol.fileListModel.getElementAt(0).fileName());

        assertFalse(globalCol.btnRemoveFolder.isEnabled());
        SwingTestUtils.runOnEdt(() -> globalCol.folderList.setSelectedIndex(0));
        assertTrue(globalCol.btnRemoveFolder.isEnabled());
        SwingTestUtils.runOnEdt(globalCol::clearSelection);
        assertFalse(globalCol.btnRemoveFolder.isEnabled());

        JLabel cellLabel = assertInstanceOf(JLabel.class, SwingTestUtils.renderListCell(projCol.fileList, 0, false));
        assertEquals("old.md", cellLabel.getText());

        when(promptService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles())
                .thenReturn(List.of(new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java"))));
        SwingTestUtils.awaitFuture(projCol.addFolderAsync("/tmp/new"));
        verify(promptService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

        SwingTestUtils.runOnEdt(() -> panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project"))));
        SwingTestUtils.awaitFuture(projCol.lastRefreshFuture);
        assertEquals(1, projCol.fileListModel.getSize());

        when(promptService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        SwingTestUtils.awaitFuture(globalCol.removeFolderAsync("/global"));
        SwingTestUtils.runOnEdt(() -> panel.onSettingsChanged(new SettingsChangedEvent(new AppSettings())));
        SwingTestUtils.awaitFuture(globalCol.lastRefreshFuture);
        assertTrue(globalCol.folderListModel.isEmpty());

        doThrow(new IllegalStateException("No active project")).when(promptService).setProjectContextFolders(anyList());
        List<String> errors = new ArrayList<>();
        panel.setErrorDialogPresenter(errors::add);
        SwingTestUtils.awaitFuture(panel.projectColumn.addFolderAsync("/tmp/folder"));
        SwingTestUtils.awaitCondition(() -> !errors.isEmpty());
        assertTrue(errors.get(0).contains("No active project"));
    }
}
