package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.ProjectService.ReferenceFolderMount;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.PromptService.ContextFileItem;

class ContextManagerPanelTest {

    @Test
    void testGlobalAndProjectContextManagementAndErrorHandling() {
        PromptService promptService = mock(PromptService.class);
        ProjectService projectService = mock(ProjectService.class);
        Path globalPath = Path.of("/global/rules.yaml");
        ContextFileItem globalItem = new ContextFileItem("/global", "rules.yaml", globalPath);
        Path projPath = Path.of("/tmp/docs/old.md");
        ContextFileItem projItem = new ContextFileItem("/tmp/docs", "old.md", projPath);

        ReferenceFolderMount globalMount = new ReferenceFolderMount("shared-lib", "/global/shared-lib",
                Path.of("/global/shared-lib"), true);
        ReferenceFolderMount projMount = new ReferenceFolderMount("billing-service", "/tmp/billing-service",
                Path.of("/tmp/billing-service"), false);

        when(projectService.getGlobalContextFolders()).thenReturn(List.of("/global"));
        when(promptService.listGlobalContextFiles()).thenReturn(List.of(globalItem));
        when(projectService.getGlobalReferenceFolders()).thenReturn(List.of("/global/shared-lib"));
        when(projectService.listGlobalReferenceFolderMounts()).thenReturn(List.of(globalMount));
        when(projectService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs"));
        when(promptService.listProjectContextFiles()).thenReturn(List.of(projItem));
        when(projectService.getProjectReferenceFolders()).thenReturn(List.of("/tmp/billing-service"));
        when(projectService.listProjectReferenceFolderMounts()).thenReturn(List.of(projMount));

        ContextManagerPanel panel = new ContextManagerPanel(promptService, projectService);
        ContextManagerPanel.ContextScopeColumn globalCol = panel.globalColumn;
        ContextManagerPanel.ContextScopeColumn projCol = panel.projectColumn;
        SwingTestUtils.awaitFuture(globalCol.lastRefreshFuture);
        SwingTestUtils.awaitFuture(projCol.lastRefreshFuture);

        assertEquals("Remove", globalCol.btnRemoveFolder.getText());
        assertEquals("Remove", globalCol.btnRemoveReferenceFolder.getText());
        assertEquals("Remove", projCol.btnRemoveFolder.getText());
        assertEquals("Remove", projCol.btnRemoveReferenceFolder.getText());
        assertNotNull(globalCol.getClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE));
        assertNotNull(projCol.getClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE));
        assertEquals("/global", globalCol.folderListModel.getElementAt(0));
        assertEquals("rules.yaml", globalCol.fileListModel.getElementAt(0).fileName());
        assertEquals(globalMount, globalCol.referenceFolderListModel.getElementAt(0));
        assertEquals("/tmp/docs", projCol.folderListModel.getElementAt(0));
        assertEquals("old.md", projCol.fileListModel.getElementAt(0).fileName());
        assertEquals(projMount, projCol.referenceFolderListModel.getElementAt(0));

        JLabel refCellLabel = assertInstanceOf(JLabel.class,
                SwingTestUtils.renderListCell(projCol.referenceFolderList, 0, false));
        assertEquals("@billing-service (/tmp/billing-service)", refCellLabel.getText());

        assertFalse(projCol.btnRemoveReferenceFolder.isEnabled());
        SwingTestUtils.runOnEdt(() -> projCol.referenceFolderList.setSelectedIndex(0));
        assertTrue(projCol.btnRemoveReferenceFolder.isEnabled());
        SwingTestUtils.awaitFuture(projCol.addReferenceFolderAsync("/tmp/auth-service"));
        verify(projectService).setProjectReferenceFolders(List.of("/tmp/billing-service", "/tmp/auth-service"));
        SwingTestUtils.awaitFuture(projCol.removeReferenceFolderAsync("/tmp/billing-service"));
        verify(projectService).setProjectReferenceFolders(List.of());

        assertFalse(globalCol.btnRemoveFolder.isEnabled());
        SwingTestUtils.runOnEdt(() -> globalCol.folderList.setSelectedIndex(0));
        assertTrue(globalCol.btnRemoveFolder.isEnabled());
        SwingTestUtils.runOnEdt(globalCol::clearSelection);
        assertFalse(globalCol.btnRemoveFolder.isEnabled());

        JLabel cellLabel = assertInstanceOf(JLabel.class, SwingTestUtils.renderListCell(projCol.fileList, 0, false));
        assertEquals("old.md", cellLabel.getText());

        when(projectService.getProjectContextFolders()).thenReturn(List.of("/tmp/docs", "/tmp/new"));
        when(promptService.listProjectContextFiles())
                .thenReturn(List.of(new ContextFileItem("/tmp/new", "new1.java", Path.of("/tmp/new/new1.java"))));
        SwingTestUtils.awaitFuture(projCol.addFolderAsync("/tmp/new"));
        verify(projectService).setProjectContextFolders(List.of("/tmp/docs", "/tmp/new"));

        SwingTestUtils.runOnEdt(() -> panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project"))));
        SwingTestUtils.awaitFuture(projCol.lastRefreshFuture);
        assertEquals(1, projCol.fileListModel.getSize());

        when(projectService.getGlobalContextFolders()).thenReturn(List.of());
        when(promptService.listGlobalContextFiles()).thenReturn(List.of());
        SwingTestUtils.awaitFuture(globalCol.removeFolderAsync("/global"));
        SwingTestUtils.runOnEdt(() -> panel.onSettingsChanged(new SettingsChangedEvent(new AppSettings())));
        SwingTestUtils.awaitFuture(globalCol.lastRefreshFuture);
        assertTrue(globalCol.folderListModel.isEmpty());

        doThrow(new IllegalStateException("No active project")).when(projectService)
                .setProjectContextFolders(anyList());
        SwingTestUtils.awaitFuture(panel.projectColumn.addFolderAsync("/tmp/folder"));
        SwingTestUtils.awaitCondition(() -> !panel.statusLabel.getText().isEmpty());
        assertTrue(panel.statusLabel.getText().contains("No active project"));
        assertTrue(panel.statusLabel.getToolTipText().contains("No active project"));
    }
}
