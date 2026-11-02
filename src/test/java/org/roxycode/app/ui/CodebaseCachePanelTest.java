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
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.PromptService;

@ExtendWith(MockitoExtension.class)
class CodebaseCachePanelTest {

    @Mock
    private PromptService promptService;
    @Mock
    private RepoMapPackerService packerService;

    @Test
    void testRefreshStatusRegenerationAndTruncateForDisplay() {
        when(packerService.forceRegenerateRepoMap()).thenReturn(new EditorResult(true, "# Initial Repo Map", null));
        when(packerService.getCachedSnapshot()).thenReturn(Optional.empty());
        CodebaseCachePanel panel = new CodebaseCachePanel(packerService, promptService);

        SwingTestUtils.awaitCondition(() -> panel.packButton.isEnabled()
                && "Status: No implicit cache found.".equals(panel.statusLabel.getText()));
        verify(packerService, times(1)).forceRegenerateRepoMap();

        RepoMapSnapshot snapshot = new RepoMapSnapshot("roxycode", "cached repo map",
                LocalDateTime.of(2026, 1, 1, 12, 0), 500L);
        when(packerService.getCachedSnapshot()).thenReturn(Optional.of(snapshot));
        when(promptService.getStableContext()).thenReturn("stable prompt");
        when(promptService.getSessionContext()).thenReturn("session prompt");

        SwingTestUtils.clickOnEdt(panel.packButton);
        SwingTestUtils.awaitCondition(
                () -> "cached repo map".equals(panel.repoMapArea.getText()) && panel.packButton.isEnabled());
        assertTrue(panel.statusLabel.getText().contains("Total: 503"));
        assertEquals("stable prompt", panel.stableContextArea.getText());
        assertEquals("session prompt", panel.sessionContextArea.getText());

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp")));
        SwingTestUtils.awaitCondition(panel.packButton::isEnabled);
        panel.onContextChanged(new ContextChangedEvent(true));
        SwingTestUtils.awaitCondition(panel.packButton::isEnabled);
        verify(packerService, atLeast(3)).forceRegenerateRepoMap();

        assertEquals("", CodebaseCachePanel.truncateForDisplay(null));
        assertEquals("short", CodebaseCachePanel.truncateForDisplay("short"));
        String truncated = CodebaseCachePanel
                .truncateForDisplay("x".repeat(CodebaseCachePanel.MAX_DISPLAY_CHARS + 100));
        assertTrue(truncated.contains("[Display truncated at 200,000 characters"));
    }
}
