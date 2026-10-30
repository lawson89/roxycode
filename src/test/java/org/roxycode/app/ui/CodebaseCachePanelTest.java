package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.PromptService;

@ExtendWith(MockitoExtension.class)
class CodebaseCachePanelTest {

    @Mock
    private PromptService promptService;
    @Mock
    private RepoMapPackerService packerService;
    @Mock
    private ProjectCacheMetaService metaService;
    private CodebaseCachePanel panel;

    @BeforeEach
    void setUp() {
        lenient().when(packerService.forceRegenerateRepoMap())
                .thenReturn(new EditorResult(true, "# Initial Repo Map", null));
        lenient().when(metaService.loadMeta()).thenReturn(Optional.empty());
        lenient().when(metaService.loadRepoCache()).thenReturn(Optional.empty());
        panel = new CodebaseCachePanel(packerService, metaService, promptService);
    }

    @Test
    void testUpdateImplicitCacheButtonCallsForceRegenerateRepoMap() {
        JButton packButton = SwingTestUtils.findButton(panel, "Update Implicit Cache");
        assertNotNull(packButton);
        SwingTestUtils.awaitCondition(() -> packButton.isEnabled()
                && "Status: No implicit cache found.".equals(panel.getStatusLabel().getText()));
        verify(packerService, times(1)).forceRegenerateRepoMap();

        when(packerService.forceRegenerateRepoMap()).thenReturn(new EditorResult(true, "# Forced Repo Map", null));
        when(metaService.loadRepoCache()).thenReturn(Optional.of("# Forced Repo Map"));

        SwingTestUtils.clickOnEdt(packButton);

        SwingTestUtils.awaitCondition(
                () -> "# Forced Repo Map".equals(panel.getRepoMapArea().getText()) && packButton.isEnabled());
        verify(packerService, times(2)).forceRegenerateRepoMap();
        assertEquals("# Forced Repo Map", panel.getRepoMapArea().getText());
    }

    @Test
    void testTruncateForDisplayHandlesNullShortAndOversizedStrings() {
        assertEquals("", CodebaseCachePanel.truncateForDisplay(null));
        assertEquals("short text", CodebaseCachePanel.truncateForDisplay("short text"));

        String exactBoundary = "a".repeat(CodebaseCachePanel.MAX_DISPLAY_CHARS);
        assertEquals(exactBoundary, CodebaseCachePanel.truncateForDisplay(exactBoundary));

        int oversizedLen = CodebaseCachePanel.MAX_DISPLAY_CHARS + 50_000;
        String suffix = String.format(
                "\n\n... [Display truncated at 200,000 characters for UI responsiveness; full content (%,d chars) is cached]",
                oversizedLen);
        String truncated = CodebaseCachePanel.truncateForDisplay("x".repeat(oversizedLen));
        assertTrue(truncated.endsWith(suffix));
        assertEquals(CodebaseCachePanel.MAX_DISPLAY_CHARS + suffix.length(), truncated.length());
    }

    @Test
    void testRefreshStatusAndRegenerationOnProjectAndContextChanges() {
        JButton packButton = SwingTestUtils.findButton(panel, "Update Implicit Cache");
        assertNotNull(packButton);
        SwingTestUtils.awaitCondition(() -> packButton.isEnabled()
                && "Status: No implicit cache found.".equals(panel.getStatusLabel().getText()));
        verify(packerService, times(1)).forceRegenerateRepoMap();

        ProjectCacheMeta meta = new ProjectCacheMeta("roxycode", LocalDateTime.of(2026, 1, 1, 12, 0), 500L);
        when(metaService.loadMeta()).thenReturn(Optional.of(meta));
        when(metaService.loadRepoCache()).thenReturn(Optional.of("cached repo map"));
        when(promptService.getStableContext()).thenReturn("stable prompt");
        when(promptService.getSessionContext()).thenReturn("session prompt");

        SwingTestUtils.awaitFuture(panel.refreshStatus());

        assertTrue(panel.getStatusLabel().getText().contains("Total: 503"));
        assertEquals("cached repo map", panel.getRepoMapArea().getText());
        assertEquals("stable prompt", panel.getStableContextArea().getText());
        assertEquals("session prompt", panel.getSessionContextArea().getText());

        when(metaService.loadRepoCache())
                .thenReturn(Optional.of("R".repeat(CodebaseCachePanel.MAX_DISPLAY_CHARS + 12_345)));
        SwingTestUtils.awaitFuture(panel.refreshStatus());
        assertTrue(panel.getRepoMapArea().getText()
                .contains("[Display truncated at 200,000 characters for UI responsiveness;"));
        assertEquals(0, panel.getRepoMapArea().getCaretPosition());

        when(metaService.loadRepoCache()).thenReturn(Optional.of("new project repo map"));
        assertFalse(SwingUtilities.isEventDispatchThread());
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp")));
        SwingTestUtils.awaitCondition(
                () -> "new project repo map".equals(panel.getRepoMapArea().getText()) && packButton.isEnabled());
        verify(packerService, times(2)).forceRegenerateRepoMap();

        when(promptService.getStableContext()).thenReturn("updated global context prompt");
        panel.onContextChanged(new ContextChangedEvent(true));
        SwingTestUtils
                .awaitCondition(() -> "updated global context prompt".equals(panel.getStableContextArea().getText())
                        && packButton.isEnabled());
        verify(packerService, times(3)).forceRegenerateRepoMap();

        when(promptService.getStableContext()).thenReturn("updated project context prompt");
        panel.onContextChanged(new ContextChangedEvent(false));
        SwingTestUtils
                .awaitCondition(() -> "updated project context prompt".equals(panel.getStableContextArea().getText())
                        && packButton.isEnabled());
        verify(packerService, times(4)).forceRegenerateRepoMap();

        when(metaService.loadMeta()).thenReturn(Optional.empty());
        when(metaService.loadRepoCache()).thenReturn(Optional.of("local only repo map"));
        SwingTestUtils.awaitFuture(panel.refreshStatus());
        assertEquals("local only repo map", panel.getRepoMapArea().getText());
        assertEquals("Status: Repo Map available locally.", panel.getStatusLabel().getText());

        when(metaService.loadRepoCache()).thenReturn(Optional.empty());
        SwingTestUtils.awaitFuture(panel.refreshStatus());
        assertEquals("Status: No implicit cache found.", panel.getStatusLabel().getText());
        assertEquals("Repo Map will appear here after generation.", panel.getRepoMapArea().getText());
    }
}
