package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
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
        lenient().when(metaService.loadMeta()).thenReturn(Optional.empty());
        lenient().when(metaService.loadRepoCache()).thenReturn(Optional.empty());
        panel = new CodebaseCachePanel(packerService, metaService, promptService);
    }

    @Test
    void testButtonsExist() {
        assertNotNull(SwingTestUtils.findButton(panel, "Update Implicit Cache"));
    }

    @Test
    void testUpdateImplicitCacheButtonCallsForceRegenerateRepoMap() {
        SwingTestUtils.awaitCondition(() -> !"Status: Unknown".equals(panel.getStatusLabel().getText()));

        when(packerService.forceRegenerateRepoMap()).thenReturn(new EditorResult(true, "# Forced Repo Map", null));
        when(metaService.loadRepoCache()).thenReturn(Optional.of("# Forced Repo Map"));

        JButton packButton = SwingTestUtils.findButton(panel, "Update Implicit Cache");
        assertNotNull(packButton);
        SwingTestUtils.clickOnEdt(packButton);

        SwingTestUtils.awaitCondition(
                () -> "# Forced Repo Map".equals(panel.getRepoMapArea().getText()) && packButton.isEnabled());
        verify(packerService).forceRegenerateRepoMap();
        assertEquals("# Forced Repo Map", panel.getRepoMapArea().getText());
    }

    @Test
    void testRepoMapAreaExists() {
        assertNotNull(SwingTestUtils.findComponent(panel, JTextArea.class));
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
    void testRefreshStatusRunsAsyncAndUpdatesAreasAndTruncatesLargeContent() {
        SwingTestUtils.awaitCondition(() -> !"Status: Unknown".equals(panel.getStatusLabel().getText()));

        ProjectCacheMeta meta = new ProjectCacheMeta("roxycode", LocalDateTime.of(2026, 1, 1, 12, 0), 500L);
        when(metaService.loadMeta()).thenReturn(Optional.of(meta));
        when(metaService.loadRepoCache()).thenReturn(Optional.of("cached repo map"));
        when(promptService.getStableContext()).thenReturn("stable prompt");
        when(promptService.getSessionContext()).thenReturn("session prompt");

        SwingTestUtils.awaitWorker(panel.refreshStatus());
        SwingTestUtils.awaitCondition(() -> "session prompt".equals(panel.getSessionContextArea().getText()));

        assertTrue(panel.getStatusLabel().getText().contains("Total: 503"));
        assertEquals("cached repo map", panel.getRepoMapArea().getText());
        assertEquals("stable prompt", panel.getStableContextArea().getText());
        assertEquals("session prompt", panel.getSessionContextArea().getText());

        when(metaService.loadRepoCache())
                .thenReturn(Optional.of("R".repeat(CodebaseCachePanel.MAX_DISPLAY_CHARS + 12_345)));
        SwingTestUtils.awaitWorker(panel.refreshStatus());
        SwingTestUtils.awaitCondition(() -> panel.getRepoMapArea().getText()
                .contains("[Display truncated at 200,000 characters for UI responsiveness;"));
        assertEquals(0, panel.getRepoMapArea().getCaretPosition());

        when(metaService.loadRepoCache()).thenReturn(Optional.of("new project repo map"));
        assertFalse(SwingUtilities.isEventDispatchThread());
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp")));
        SwingTestUtils.awaitCondition(() -> "new project repo map".equals(panel.getRepoMapArea().getText()));

        when(metaService.loadMeta()).thenReturn(Optional.empty());
        when(metaService.loadRepoCache()).thenReturn(Optional.of("local only repo map"));
        SwingTestUtils.awaitWorker(panel.refreshStatus());
        SwingTestUtils.awaitCondition(() -> "local only repo map".equals(panel.getRepoMapArea().getText()));
        assertEquals("Status: Repo Map available locally.", panel.getStatusLabel().getText());

        when(metaService.loadRepoCache()).thenReturn(Optional.empty());
        SwingTestUtils.awaitWorker(panel.refreshStatus());
        SwingTestUtils
                .awaitCondition(() -> "Status: No implicit cache found.".equals(panel.getStatusLabel().getText()));
        assertEquals("Repo Map will appear here after generation.", panel.getRepoMapArea().getText());
    }
}
