package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService.RepoMapSnapshot;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.events.MemoryChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.PromptService;

@ExtendWith(MockitoExtension.class)
class CodebaseCachePanelTest {

    @Mock
    private PromptService promptService;
    @Mock
    private RepoMapPackerService packerService;

    @Test
    void testRefreshStatusTokenBreakdownCopyAndEvents() {
        when(packerService.forceRegenerateRepoMap()).thenReturn(new EditorResult(true, "# Initial Repo Map", null));
        when(packerService.getCachedSnapshot()).thenReturn(Optional.empty());
        CodebaseCachePanel panel = new CodebaseCachePanel(packerService, promptService, null);

        SwingTestUtils.awaitCondition(() -> panel.packButton.isEnabled()
                && CodebaseCachePanel.NO_SNAPSHOT_STATUS.equals(panel.statusLabel.getText()));
        assertEquals("Repo Map & Prompt", panel.titleLabel.getText());
        assertEquals("Refresh Repo Map", panel.packButton.getText());
        verify(packerService, times(1)).forceRegenerateRepoMap();

        RepoMapSnapshot snapshot = new RepoMapSnapshot("roxycode", "cached repo map",
                LocalDateTime.of(2026, 1, 1, 12, 0), 500L);
        when(packerService.getCachedSnapshot()).thenReturn(Optional.of(snapshot));
        when(promptService.getStableContext()).thenReturn("1234567890123456789012345678901234567890");
        when(promptService.loadGlobalContext()).thenReturn("12345678");
        when(promptService.loadProjectContext()).thenReturn("1234");
        when(promptService.loadAgentMemoryContext()).thenReturn("12345678");
        when(promptService.getSessionContext()).thenReturn("123456789012");

        SwingTestUtils.clickOnEdt(panel.packButton);
        SwingTestUtils.awaitCondition(
                () -> "cached repo map".equals(panel.repoMapArea.getText()) && panel.packButton.isEnabled());
        assertTrue(panel.statusLabel.getText().contains("Repo Map: 500 | Stable: 10 | Session: 3 | Total: 513"));
        assertTrue(
                panel.stableTokensLabel.getText().contains("[Workflow/JEXL: 5 • Context Files: 3 • Agent Memory: 2]"));
        assertTrue(panel.totalTokensLabel.getText().contains("Total Prompt Prefix Tokens: 513"));
        assertEquals("1234567890123456789012345678901234567890", panel.stableContextArea.getText());
        assertEquals("123456789012", panel.sessionContextArea.getText());

        SwingTestUtils.runOnEdt(() -> {
            panel.tabbedPane.setSelectedIndex(0);
            panel.copyCurrentTabContent();
        });
        assertEquals("cached repo map", panel.lastCopiedContent);

        SwingTestUtils.runOnEdt(() -> {
            panel.tabbedPane.setSelectedIndex(2);
            panel.copyCurrentTabContent();
        });
        assertEquals("123456789012", panel.lastCopiedContent);

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp")));
        SwingTestUtils.awaitCondition(panel.packButton::isEnabled);
        verify(packerService, atLeast(2)).forceRegenerateRepoMap();

        when(promptService.getSessionContext()).thenReturn("updated session context after memory change");
        panel.onContextChanged(new ContextChangedEvent(true));
        panel.onMemoryChanged(new MemoryChangedEvent(true));
        SwingTestUtils.awaitCondition(
                () -> "updated session context after memory change".equals(panel.sessionContextArea.getText()));

        assertEquals("", CodebaseCachePanel.truncateForDisplay(null));
        assertEquals("short", CodebaseCachePanel.truncateForDisplay("short"));
        String truncated = CodebaseCachePanel
                .truncateForDisplay("x".repeat(CodebaseCachePanel.MAX_DISPLAY_CHARS + 100));
        assertTrue(truncated.contains("[Display truncated at 200,000 characters"));
    }
}
