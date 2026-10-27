package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import java.util.List;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.ui.ArchiveBrowserPanel.ArchiveMode;

class ArchiveBrowserPanelTest {

    @Test
    void testTopicsModePopulationSelectionCopyAndSearch() throws Exception {
        MemoryService memoryService = mock(MemoryService.class);
        ArchiveEntrySummary topic1 =
                new ArchiveEntrySummary(
                        "20250514-153000-first.md",
                        "topic",
                        "First Topic Title",
                        "Alice",
                        "2025-05-14T15:30:00Z",
                        4,
                        "");
        ArchiveEntrySummary topic2 =
                new ArchiveEntrySummary(
                        "20250513-100000-second.md",
                        "topic",
                        "Second Topic Title",
                        "Bob",
                        "2025-05-13T10:00:00Z",
                        2,
                        "Matched line in second topic");

        String rawMd1 =
                """
                ---
                id: "20250514-153000-first"
                title: "First Topic Title"
                ---

                # Topic: First Topic Title

                ### User (15:30:00)
                Hello world
                """;
        String rawMd2 =
                """
                ---
                id: "20250513-100000-second"
                title: "Second Topic Title"
                ---

                # Topic: Second Topic Title

                Matched line in second topic
                """;

        when(memoryService.listArchivedTopicSummaries()).thenReturn(List.of(topic1, topic2));
        when(memoryService.readArchivedTopic("20250514-153000-first.md")).thenReturn(rawMd1);
        when(memoryService.readArchivedTopic("20250513-100000-second.md")).thenReturn(rawMd2);
        when(memoryService.searchArchivedTopics("Second")).thenReturn(List.of(topic2));
        when(memoryService.searchArchivedTopics("NoMatch")).thenReturn(List.of());

        ArchiveBrowserPanel panel = new ArchiveBrowserPanel(memoryService, ArchiveMode.TOPICS);
        assertEquals(ArchiveMode.TOPICS, panel.getMode());
        assertEquals("0 topics", panel.getCountLabel().getText());
        assertFalse(panel.getCopyMarkdownButton().isEnabled());

        panel.refresh().get();
        SwingUtilities.invokeAndWait(() -> {});
        if (panel.getActiveReadWorker() != null) {
            panel.getActiveReadWorker().get();
            SwingUtilities.invokeAndWait(() -> {});
        }

        assertEquals(2, panel.getListModel().getSize());
        assertEquals("2 topics", panel.getCountLabel().getText());
        assertEquals(0, panel.getEntryList().getSelectedIndex());
        assertEquals("First Topic Title", panel.getDetailSummaryLabel().getText());
        assertTrue(panel.getCopyMarkdownButton().isEnabled());

        String html = panel.getDisplayArea().getText();
        assertTrue(html.contains("First Topic Title"));
        assertTrue(html.contains("Hello world"));
        assertFalse(html.contains("id: &quot;20250514-153000-first&quot;"));

        // Verify cell renderer renders both normal and snippet items cleanly
        JList<ArchiveEntrySummary> list = panel.getEntryList();
        Component cell0 =
                list.getCellRenderer().getListCellRendererComponent(list, topic1, 0, true, true);
        assertNotNull(cell0);
        Component cell1 =
                list.getCellRenderer().getListCellRendererComponent(list, topic2, 1, false, false);
        assertNotNull(cell1);

        // Copy as Markdown
        panel.getCopyMarkdownButton().doClick();
        assertEquals(rawMd1, panel.getLastCopiedMarkdown());
        assertEquals("Copied!", panel.getCopyMarkdownButton().getText());

        // Search filtering
        panel.getSearchField().setText("Second");
        panel.executeSearchAsync().get();
        SwingUtilities.invokeAndWait(() -> {});
        if (panel.getActiveReadWorker() != null) {
            panel.getActiveReadWorker().get();
            SwingUtilities.invokeAndWait(() -> {});
        }

        assertEquals(1, panel.getListModel().getSize());
        assertEquals("1 topic", panel.getCountLabel().getText());
        assertEquals("Second Topic Title", panel.getDetailSummaryLabel().getText());
        assertTrue(panel.getDisplayArea().getText().contains("Matched line in second topic"));

        // Empty search state
        panel.getSearchField().setText("NoMatch");
        panel.executeSearchAsync().get();
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, panel.getListModel().getSize());
        assertEquals("0 topics", panel.getCountLabel().getText());
        assertFalse(panel.getCopyMarkdownButton().isEnabled());
        assertTrue(panel.getDisplayArea().getText().contains("No matching topics found."));

        panel.updateUI();
    }

    @Test
    void testPlansModePopulationAndRendering() throws Exception {
        MemoryService memoryService = mock(MemoryService.class);
        ArchiveEntrySummary plan1 =
                new ArchiveEntrySummary(
                        "20250514-120000-plan.md",
                        "plan",
                        "Feature Plan Alpha",
                        "Charlie",
                        "2025-05-14T12:00:00Z",
                        0,
                        "");
        String rawPlanMd =
                """
                ---
                id: "20250514-120000-plan"
                title: "Feature Plan Alpha"
                ---
                # Implementation Plan: Feature Plan Alpha
                - [x] 0. First step
                """;

        when(memoryService.listArchivedPlanSummaries()).thenReturn(List.of(plan1));
        when(memoryService.readArchivedPlan("20250514-120000-plan.md")).thenReturn(rawPlanMd);

        ArchiveBrowserPanel panel = new ArchiveBrowserPanel(memoryService, ArchiveMode.PLANS);
        assertEquals(ArchiveMode.PLANS, panel.getMode());

        panel.refresh().get();
        SwingUtilities.invokeAndWait(() -> {});
        if (panel.getActiveReadWorker() != null) {
            panel.getActiveReadWorker().get();
            SwingUtilities.invokeAndWait(() -> {});
        }

        assertEquals(1, panel.getListModel().getSize());
        assertEquals("1 plan", panel.getCountLabel().getText());
        assertEquals("Feature Plan Alpha", panel.getDetailSummaryLabel().getText());
        assertTrue(panel.getDisplayArea().getText().contains("Completed On:"));
        assertTrue(panel.getDisplayArea().getText().contains("Charlie"));
    }
}
