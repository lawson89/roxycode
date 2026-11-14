package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.MemoryEntry;
import org.roxycode.app.events.MemoryChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class AgentMemoryPanelTest {

    @TempDir
    Path tempDir;

    private SettingsService settingsService;
    private ProjectService projectService;
    private MemoryService memoryService;
    private Path roxyHomeDir;
    private Path projectDataDir;

    @BeforeEach
    void setUp() throws IOException {
        roxyHomeDir = Files.createDirectories(tempDir.resolve("roxyhome"));
        projectDataDir = Files.createDirectories(tempDir.resolve("project-data"));
        settingsService = mock(SettingsService.class);
        projectService = mock(ProjectService.class);
        when(settingsService.getRoxyHomeDir()).thenReturn(roxyHomeDir);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
        memoryService = new MemoryService(settingsService, projectService, null);
    }

    @Test
    void testMasterDetailCrudAutoSlugRevertSearchAndTabBadges() {
        when(projectService.hasActiveProject()).thenReturn(false);
        memoryService.put("global", "no-ternary", "Never use ternary operators.", List.of("java"),
                "Always use explicit if/else blocks.");

        AgentMemoryPanel panel = new AgentMemoryPanel(memoryService, projectService, settingsService);
        SwingTestUtils.awaitFuture(panel.globalScopeView.lastAsyncFuture);
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);

        assertFalse(panel.projectScopeView.isScopeAvailable());
        assertEquals("Project Memory (0)", panel.scopeTabbedPane.getTitleAt(0));
        assertEquals("Global Memory (1)", panel.scopeTabbedPane.getTitleAt(1));
        assertEquals(1, panel.globalScopeView.listModel.size());

        SwingTestUtils.runOnEdt(() -> panel.scopeTabbedPane.setSelectedIndex(1));
        assertTrue(panel.promptFootprintBadge.getText().contains("Always-On Prompt: ~"));

        // Switch project on
        when(projectService.hasActiveProject()).thenReturn(true);
        panel.onProjectChanged(new ProjectChangedEvent(projectDataDir));
        SwingTestUtils.flushEdt();
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);
        assertTrue(panel.projectScopeView.isScopeAvailable());

        // Create new entry with auto-derived slug
        SwingTestUtils.runOnEdt(() -> {
            panel.projectScopeView.startNewEntry();
            panel.projectScopeView.summaryArea.setText("Swing HTML 3.2 Rules!");
            panel.projectScopeView.tagsField.setText("ui, swing");
            panel.projectScopeView.detailsEditor.setText("Always use HTML tables for layout.");
        });
        assertEquals("swing-html-3-2-rules", panel.projectScopeView.idField.getText());
        assertTrue(panel.projectScopeView.saveButton.isEnabled());
        assertTrue(panel.projectScopeView.revertButton.isEnabled());

        SwingTestUtils.clickOnEdt(panel.projectScopeView.saveButton);
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);
        SwingTestUtils.flushEdt();
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);

        assertEquals(1, panel.projectScopeView.listModel.size());
        assertEquals("Project Memory (1)", panel.scopeTabbedPane.getTitleAt(0));
        MemoryEntry saved = memoryService.read("project", "swing-html-3-2-rules");
        assertNotNull(saved);
        assertEquals("Always use HTML tables for layout.", saved.details());

        // External memory update via event
        memoryService.put("project", "caffeine-cache", "Use AsyncCache.", List.of("cache", "async"), "");
        panel.onMemoryChanged(new MemoryChangedEvent(false));
        SwingTestUtils.flushEdt();
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);
        assertEquals(2, panel.projectScopeView.listModel.size());

        // Search filter
        SwingTestUtils.runOnEdt(() -> panel.projectScopeView.searchField.setText("caffeine"));
        assertEquals(1, panel.projectScopeView.listModel.size());
        assertEquals("caffeine-cache", panel.projectScopeView.listModel.get(0).id());

        SwingTestUtils.runOnEdt(() -> {
            panel.projectScopeView.searchField.setText("");
            panel.projectScopeView.entryList.setSelectedIndex(0);
        });
        assertEquals(2, panel.projectScopeView.listModel.size());

        // Revert dirty edits
        SwingTestUtils.runOnEdt(() -> panel.projectScopeView.summaryArea.setText("Unsaved summary edit"));
        assertTrue(panel.projectScopeView.isEditorDirty());
        SwingTestUtils.clickOnEdt(panel.projectScopeView.revertButton);
        assertFalse(panel.projectScopeView.isEditorDirty());

        // Delete with InlineConfirmBar
        String selectedId = panel.projectScopeView.entryList.getSelectedValue().id();
        SwingTestUtils.clickOnEdt(panel.projectScopeView.deleteButton);
        UiComponents.InlineConfirmBar confirmBar = panel.projectScopeView.deleteConfirmBar;
        assertTrue(confirmBar.isPromptVisible());
        assertEquals("Delete memory '" + selectedId + "'?", confirmBar.getMessage());
        SwingTestUtils.clickOnEdt(confirmBar.confirmButton);
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);
        SwingTestUtils.flushEdt();
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);

        assertFalse(confirmBar.isPromptVisible());
        assertEquals(1, panel.projectScopeView.listModel.size());
        assertNull(memoryService.read("project", selectedId));
    }
}
