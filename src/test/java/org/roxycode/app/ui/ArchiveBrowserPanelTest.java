package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.util.List;
import javax.swing.JList;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.ui.ArchiveBrowserPanel.ArchiveMode;

class ArchiveBrowserPanelTest {

    @Test
    void testTopicsModePopulationSelectionCopyAndSearch() throws Exception {
        MemoryService memoryService = mock(MemoryService.class);
        ArchiveEntrySummary topic1 = new ArchiveEntrySummary("20250514-153000-first.md", "topic", "First Topic Title",
                "Alice", "2025-05-14T15:30:00Z", 4, "");
        ArchiveEntrySummary topic2 = new ArchiveEntrySummary("20250513-100000-second.md", "topic", "Second Topic Title",
                "Bob", "2025-05-13T10:00:00Z", 2, "Matched line in second topic");

        String rawMd1 = "---\nid: '20250514-153000-first'\ntitle: 'First Topic Title'\n---\n\n# Topic: First Topic Title\n\n### User (15:30:00)\nHello world\n";
        String rawMd2 = "---\nid: '20250513-100000-second'\ntitle: 'Second Topic Title'\n---\n\n# Topic: Second Topic Title\n\nMatched line in second topic\n";

        when(memoryService.listArchivedTopicSummaries()).thenReturn(List.of(topic1, topic2));
        when(memoryService.readArchivedTopic("20250514-153000-first.md")).thenReturn(rawMd1);
        when(memoryService.readArchivedTopic("20250513-100000-second.md")).thenReturn(rawMd2);
        when(memoryService.searchArchivedTopics("Second")).thenReturn(List.of(topic2));
        when(memoryService.searchArchivedTopics("NoMatch")).thenReturn(List.of());

        ArchiveBrowserPanel panel = new ArchiveBrowserPanel(memoryService, ArchiveMode.TOPICS);
        assertEquals(ArchiveMode.TOPICS, panel.mode);
        assertEquals("0 topics", panel.countLabel.getText());
        assertFalse(panel.copyMarkdownButton.isEnabled());

        SwingTestUtils.awaitFuture(panel.refresh());

        assertEquals(2, panel.listModel.getSize());
        assertEquals("2 topics", panel.countLabel.getText());
        assertEquals(0, panel.entryList.getSelectedIndex());
        assertEquals("First Topic Title", panel.detailSummaryLabel.getText());
        assertTrue(panel.copyMarkdownButton.isEnabled());

        String html = panel.displayArea.getText();
        assertContainsAll(html, "First Topic Title", "Hello world");
        assertFalse(html.contains("id:"));

        JList<ArchiveEntrySummary> list = panel.entryList;
        assertNotNull(SwingTestUtils.renderListCell(list, 0, true));
        assertNotNull(SwingTestUtils.renderListCell(list, 1, false));

        SwingTestUtils.clickOnEdt(panel.copyMarkdownButton);
        assertEquals(rawMd1, panel.lastCopiedMarkdown);
        assertEquals("Copied!", panel.copyMarkdownButton.getText());

        panel.searchField.setText("Second");
        SwingTestUtils.awaitFuture(panel.executeSearchAsync());

        assertEquals(1, panel.listModel.getSize());
        assertEquals("1 topic", panel.countLabel.getText());
        assertEquals("Second Topic Title", panel.detailSummaryLabel.getText());
        assertTrue(panel.displayArea.getText().contains("Matched line in second topic"));

        panel.searchField.setText("NoMatch");
        SwingTestUtils.awaitFuture(panel.executeSearchAsync());
        assertEquals(0, panel.listModel.getSize());
        assertEquals("0 topics", panel.countLabel.getText());
        assertFalse(panel.copyMarkdownButton.isEnabled());
        assertTrue(panel.displayArea.getText().contains("No matching topics found."));

        SwingTestUtils.withDarkTheme(panel,
                () -> assertTrue(panel.displayArea.getText().contains("No matching topics found.")));
    }

    @Test
    void testPlansModePopulationAndRendering() {
        MemoryService memoryService = mock(MemoryService.class);
        ArchiveEntrySummary plan1 = new ArchiveEntrySummary("20250514-120000-plan.md", "plan", "Feature Plan Alpha",
                "Charlie", "2025-05-14T12:00:00Z", 0, "");
        String rawPlanMd = "---\nid: '20250514-120000-plan'\ntitle: 'Feature Plan Alpha'\n---\n# Implementation Plan: Feature Plan Alpha\n- [x] 0. First step\n";

        when(memoryService.listArchivedPlanSummaries()).thenReturn(List.of(plan1));
        when(memoryService.readArchivedPlan("20250514-120000-plan.md")).thenReturn(rawPlanMd);

        ArchiveBrowserPanel panel = new ArchiveBrowserPanel(memoryService, ArchiveMode.PLANS);
        assertEquals(ArchiveMode.PLANS, panel.mode);

        SwingTestUtils.awaitFuture(panel.refresh());
        assertEquals(1, panel.listModel.getSize());
        assertEquals("1 plan", panel.countLabel.getText());
        assertEquals("Feature Plan Alpha", panel.detailSummaryLabel.getText());
        assertContainsAll(panel.displayArea.getText(), "Completed On:", "Charlie");
    }

    @Test
    void testBuildDetailHtmlHighlightsCodeBlocksOnceWithoutNestedSpanCorruption() {
        ArchiveEntrySummary entry = new ArchiveEntrySummary("20250514-120000-code.md", "topic", "Code Topic", "Alice",
                "2025-05-14T12:00:00Z", 1, "");
        String bt3 = String.valueOf((char) 96).repeat(3);
        String markdownWithCode = "---\nid: '20250514-120000-code'\ntitle: 'Code Topic'\n---\n\n" + bt3
                + "java\npublic class Demo {\n    int x = 1;\n}\n" + bt3 + "\n";
        String detailHtml = ArchiveBrowserPanel.buildDetailHtml(entry, markdownWithCode, ArchiveMode.TOPICS);
        assertContainsAll(detailHtml, "<span class='tk-rw'>public</span>", "<span class='tk-nm'>1</span>");
        assertFalse(detailHtml.contains("<span class=<span"));
    }
}
