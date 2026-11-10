package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.ui.ArchiveBrowserPanel.ArchiveMode;

class ArchiveBrowserPanelTest {

    @Test
    void testTopicsAndPlansPopulationSearchCopyAndCodeHighlighting() {
        MemoryService memoryService = mock(MemoryService.class);
        ArchiveEntrySummary topic1 = new ArchiveEntrySummary("20250514-153000-first.md", "topic", "First Topic Title",
                "Alice", "2025-05-14T15:30:00Z", 4, "", 0L, 0L, 0, 0.0);
        ArchiveEntrySummary topic2 = new ArchiveEntrySummary("20250513-100000-second.md", "topic", "Second Topic Title",
                "Bob", "2025-05-13T10:00:00Z", 2, "Matched line in second topic", 0L, 0L, 0, 0.0);

        String rawMd1 = "---\nid: '20250514-153000-first'\ntitle: 'First Topic Title'\n---\n\n# Topic: First Topic Title\n\nHello world\n";
        String rawMd2 = "---\nid: '20250513-100000-second'\ntitle: 'Second Topic Title'\n---\n\nMatched line in second topic\n";

        when(memoryService.listArchivedTopicSummaries()).thenReturn(List.of(topic1, topic2));
        when(memoryService.readArchivedTopic("20250514-153000-first.md")).thenReturn(rawMd1);
        when(memoryService.readArchivedTopic("20250513-100000-second.md")).thenReturn(rawMd2);
        when(memoryService.searchArchivedTopics("Second")).thenReturn(List.of(topic2));
        when(memoryService.searchArchivedTopics("NoMatch")).thenReturn(List.of());

        ArchiveBrowserPanel panel = new ArchiveBrowserPanel(memoryService, ArchiveMode.TOPICS);
        SwingTestUtils.awaitFuture(panel.refresh());

        assertEquals(2, panel.listModel.getSize());
        assertEquals("2 topics", panel.countLabel.getText());
        assertEquals("First Topic Title", panel.detailSummaryLabel.getText());
        assertContainsAll(panel.displayArea.getText(), "First Topic Title", "Hello world");
        assertNotNull(SwingTestUtils.renderListCell(panel.entryList, 0, true));

        SwingTestUtils.clickOnEdt(panel.copyMarkdownButton);
        assertEquals(rawMd1, panel.lastCopiedMarkdown);

        panel.searchField.setText("Second");
        SwingTestUtils.awaitFuture(panel.executeSearchAsync());
        assertEquals(1, panel.listModel.getSize());
        assertTrue(panel.displayArea.getText().contains("Matched line in second topic"));

        panel.searchField.setText("NoMatch");
        SwingTestUtils.awaitFuture(panel.executeSearchAsync());
        assertEquals(0, panel.listModel.getSize());
        assertTrue(panel.displayArea.getText().contains("No matching topics found."));

        ArchiveEntrySummary plan1 = new ArchiveEntrySummary("20250514-120000-plan.md", "plan", "Feature Plan Alpha",
                "Charlie", "2025-05-14T12:00:00Z", 0, "", 0L, 0L, 0, 0.0);
        when(memoryService.listArchivedPlanSummaries()).thenReturn(List.of(plan1));
        when(memoryService.readArchivedPlan("20250514-120000-plan.md")).thenReturn("# Plan\n- [x] 0. First step\n");

        ArchiveBrowserPanel plansPanel = new ArchiveBrowserPanel(memoryService, ArchiveMode.PLANS);
        SwingTestUtils.awaitFuture(plansPanel.refresh());
        assertEquals("1 plan", plansPanel.countLabel.getText());
        assertContainsAll(plansPanel.displayArea.getText(), "Completed On:", "Charlie");

        String bt3 = "```";
        String detailHtml = ArchiveBrowserPanel.buildDetailHtml(topic1,
                "---\nid: '1'\n---\n\n" + bt3 + "java\npublic class Demo { int x = 1; }\n" + bt3 + "\n",
                ArchiveMode.TOPICS);
        assertContainsAll(detailHtml, "<span class='mtk-keyword'>public</span>", "<span class='mtk-number'>1</span>");
        assertFalse(detailHtml.contains("<span class=<span"));
    }
}
