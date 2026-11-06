package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.MemoryService;
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
    void testPinnedMemoryEditorCounterWarningSaveAndRevert() {
        memoryService.updateGlobalMemory("# Initial Global Memory");
        AgentMemoryPanel panel = new AgentMemoryPanel(memoryService, projectService, settingsService);
        SwingTestUtils.awaitFuture(panel.globalScopeView.lastAsyncFuture);
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);

        assertEquals("# Initial Global Memory", panel.globalScopeView.memoryEditor.getText());
        assertFalse(panel.globalScopeView.isMemoryDirty());
        assertFalse(panel.globalScopeView.saveMemoryButton.isEnabled());
        assertFalse(panel.globalScopeView.isMemoryWarningVisible());

        SwingTestUtils.runOnEdt(() -> panel.globalScopeView.memoryEditor.setText("# Modified Memory\nLine 2"));
        assertTrue(panel.globalScopeView.isMemoryDirty());
        assertTrue(panel.globalScopeView.saveMemoryButton.isEnabled());
        assertTrue(panel.globalScopeView.revertMemoryButton.isEnabled());
        assertTrue(panel.globalScopeView.memoryCounterLabel.getText().contains("2 / 200 lines"));

        SwingTestUtils.clickOnEdt(panel.globalScopeView.revertMemoryButton);
        assertEquals("# Initial Global Memory", panel.globalScopeView.memoryEditor.getText());
        assertFalse(panel.globalScopeView.isMemoryDirty());

        String oversized = "x".repeat(MemoryService.MAX_PINNED_MEMORY_CHARS + 50);
        SwingTestUtils.runOnEdt(() -> panel.globalScopeView.memoryEditor.setText(oversized));
        assertTrue(panel.globalScopeView.isMemoryWarningVisible());

        SwingTestUtils.runOnEdt(() -> panel.globalScopeView.memoryEditor.setText("# Saved Global Memory"));
        assertFalse(panel.globalScopeView.isMemoryWarningVisible());
        SwingTestUtils.clickOnEdt(panel.globalScopeView.saveMemoryButton);
        SwingTestUtils.awaitFuture(panel.globalScopeView.lastAsyncFuture);

        assertEquals("# Saved Global Memory", memoryService.readGlobalMemory());
        assertFalse(panel.globalScopeView.isMemoryDirty());
    }

    @Test
    void testTopicNotesCrudAutoSlugSearchAndProjectScopeState() {
        when(projectService.hasActiveProject()).thenReturn(false);
        AgentMemoryPanel panel = new AgentMemoryPanel(memoryService, projectService, settingsService);
        SwingTestUtils.awaitFuture(panel.globalScopeView.lastAsyncFuture);
        assertFalse(panel.projectScopeView.isScopeAvailable());

        when(projectService.hasActiveProject()).thenReturn(true);
        panel.onProjectChanged(new ProjectChangedEvent(projectDataDir));
        SwingTestUtils.flushEdt();
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);
        assertTrue(panel.projectScopeView.isScopeAvailable());

        SwingTestUtils.runOnEdt(() -> {
            panel.projectScopeView.memoryEditor.setText("# Unsaved Draft Memory");
            panel.projectScopeView.startNewNote();
            panel.projectScopeView.noteTitleField.setText("Swing HTML 3.2 Rules!");
            panel.projectScopeView.noteTagsField.setText("ui, swing");
            panel.projectScopeView.noteBodyEditor.setText("Always use HTML tables for layout.");
        });
        assertTrue(panel.projectScopeView.isMemoryDirty());
        assertEquals("swing-html-3-2-rules", panel.projectScopeView.noteSlugField.getText());

        SwingTestUtils.clickOnEdt(panel.projectScopeView.saveNoteButton);
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);

        assertEquals("# Unsaved Draft Memory", panel.projectScopeView.memoryEditor.getText());
        assertTrue(panel.projectScopeView.isMemoryDirty());
        assertEquals(1, panel.projectScopeView.noteListModel.size());
        assertEquals("Always use HTML tables for layout.",
                memoryService.readProjectNoteDetail("swing-html-3-2-rules").body());

        memoryService.saveProjectNote("caffeine-cache", "Caffeine Async Cache", "cache, async", "Use AsyncCache.");
        panel.onMemoryChanged(new MemoryChangedEvent(false));
        SwingTestUtils.flushEdt();
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);
        assertEquals(2, panel.projectScopeView.noteListModel.size());

        SwingTestUtils.runOnEdt(() -> panel.projectScopeView.searchField.setText("caffeine"));
        assertEquals(1, panel.projectScopeView.noteListModel.size());
        assertEquals("caffeine-cache", panel.projectScopeView.noteListModel.get(0).slug());

        SwingTestUtils.runOnEdt(() -> {
            panel.projectScopeView.searchField.setText("");
            panel.projectScopeView.noteList.setSelectedIndex(0);
        });
        assertEquals(2, panel.projectScopeView.noteListModel.size());

        String selectedSlug = panel.projectScopeView.noteList.getSelectedValue().slug();
        SwingTestUtils.clickOnEdt(panel.projectScopeView.deleteNoteButton);
        UiComponents.InlineConfirmBar confirmBar = panel.projectScopeView.deleteConfirmBar;
        assertTrue(confirmBar.isPromptVisible());
        assertEquals("Delete '" + selectedSlug + "'?", confirmBar.getMessage());
        assertEquals("Confirm Delete", confirmBar.confirmButton.getText());
        SwingTestUtils.runOnEdt(confirmBar::cancel);
        assertFalse(confirmBar.isPromptVisible());
        assertEquals(2, panel.projectScopeView.noteListModel.size());

        SwingTestUtils.clickOnEdt(panel.projectScopeView.deleteNoteButton);
        assertTrue(confirmBar.isPromptVisible());
        SwingTestUtils.runOnEdt(() -> panel.projectScopeView.noteList.setSelectedIndex(1));
        assertFalse(confirmBar.isPromptVisible());

        SwingTestUtils.runOnEdt(() -> panel.projectScopeView.noteList.setSelectedIndex(0));
        SwingTestUtils.clickOnEdt(panel.projectScopeView.deleteNoteButton);
        assertTrue(confirmBar.isPromptVisible());
        SwingTestUtils.clickOnEdt(confirmBar.confirmButton);
        SwingTestUtils.awaitFuture(panel.projectScopeView.lastAsyncFuture);

        assertFalse(confirmBar.isPromptVisible());
        assertEquals(1, panel.projectScopeView.noteListModel.size());
        assertEquals("Deleted note: " + selectedSlug, panel.projectScopeView.statusLabel.getText());

        SwingTestUtils.runOnEdt(() -> {
            panel.projectScopeView.startNewNote();
            panel.projectScopeView.noteTitleField.setText("   ");
            panel.projectScopeView.noteSlugField.setText("!!!");
        });
        SwingTestUtils.clickOnEdt(panel.projectScopeView.saveNoteButton);
        assertTrue(panel.projectScopeView.statusLabel.getText().contains("Note slug"));
        assertNotNull(panel.projectScopeView.statusLabel.getToolTipText());
    }
}
