package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.model.ImplementationPlan;

class HtmlExportServiceTest {

    @AfterEach
    void tearDown() {
        HtmlExportService.setBrowserLauncherForTesting(null);
        PlantUmlRenderer.clearCacheForTesting();
    }

    @Test
    void testChatExportWithOptionAToolDetailsThumbnailsAndFileBadges() {
        String aiMarkdown = String.join("\n", "Here is the summary:", "", "| Key | Value |", "| --- | --- |",
                "| Mode | HTML5 |");
        List<MarkdownPane.PersistedChatEntry> entries = List.of(
                new MarkdownPane.PersistedChatEntry(ChatHtmlTemplates.ROLE_USER, "Explain HtmlExportService",
                        "12:00:00", false, true, null, true, List.of("iVBORw0KGgo="), List.of("Main.java")),
                new MarkdownPane.PersistedChatEntry(null, "fileReadService.readFile('Main.java');", "12:00:01", true,
                        true, null, false, List.of(), List.of()),
                new MarkdownPane.PersistedChatEntry(null, "badCall();", "12:00:02", true, false, "Syntax error", true,
                        List.of(), List.of()),
                new MarkdownPane.PersistedChatEntry(ChatHtmlTemplates.ROLE_AI, aiMarkdown, "12:00:05", false, true,
                        null, true, List.of(), List.of()));

        String html = HtmlExportService.buildChatExportHtml("roxycode", "Ask Roxy — Chat Session", entries);
        assertContainsAll(html, "<!DOCTYPE html>", "@media print", "Print / Save as PDF", "window.print()", "card-user",
                "card-ai", "Main.java", "data:image/png;base64,iVBORw0KGgo=", "<details class='tool-details'>",
                "tool-status-ok", "fileReadService.readFile", "<details class='tool-details tool-details-error'>",
                "tool-status-err", "Syntax error", "<table>", "<th>Key</th>", "<td>HTML5</td>");
        assertFalse(html.contains("<details class='tool-details' open"),
                "Option A tool details should be collapsed by default");
    }

    @Test
    void testPlanAndArchiveExportsStripFrontmatterAndIncludeMetadata() {
        ImplementationPlan plan = new ImplementationPlan("Export Feature", "Build HTML5 export",
                List.of("Support print"),
                List.of(new ImplementationPlan.TechStep("Implement service", true),
                        new ImplementationPlan.TechStep("Add tests", false)),
                true, "2026-11-16T12:00:00", "lawson89", null, null, List.of());

        String planHtml = HtmlExportService.buildPlanExportHtml("roxycode", plan);
        assertContainsAll(planHtml, "Export Feature", "1 / 2 steps completed", "Approved by:", "lawson89",
                "Build HTML5 export");

        ArchiveEntrySummary entry = new ArchiveEntrySummary("20261116-120000-topic.md", "topic", "Archived Topic",
                "Alice", "2026-11-16T12:00:00Z", 3, "", 0L, 0L, 0, 0.0);
        String rawMd = String.join("\n", "---", "id: '20261116-120000-topic'", "title: 'Archived Topic'", "---", "",
                "# Heading", "", "Body text");
        String archiveHtml = HtmlExportService.buildArchiveExportHtml("roxycode", entry, rawMd,
                ArchiveBrowserPanel.ArchiveMode.TOPICS);
        assertContainsAll(archiveHtml, "Archived Topic", "Archived On:", "Alice", "Messages:", "3", "<h1>Heading</h1>",
                "Body text");
        assertFalse(archiveHtml.contains("id: '20261116-120000-topic'"), "YAML frontmatter should be stripped");
    }

    @Test
    void testVectorPlantUmlSvgDitaaFallbackAndDiagramOpenHyperlink() throws Exception {
        PlantUmlRenderer.clearCacheForTesting();
        String puml = "Alice -> Bob: Hello";
        String bt = String.valueOf((char) 96).repeat(3);
        String mdWithDiagrams = String.join("\n", bt + "plantuml", puml, bt, "", bt + "svg",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50' width='100' height='50'><rect width='100' height='50' fill='#fff'/></svg>",
                bt, "", bt + "ditaa", "+---+", "| A |", "+---+", bt);

        String exportedHtml = HtmlExportService.renderMarkdownToHtml5(mdWithDiagrams);
        assertContainsAll(exportedHtml, "<div class='diagram-export'>", "<svg", "<rect", "data:image/png;base64,");
        assertFalse(exportedHtml.contains("<?xml"), "Inline SVG should strip leading XML declaration");

        Optional<String> swingHtml = PlantUmlRenderer.renderDiagramHtml(puml, "plantuml", true);
        assertTrue(swingHtml.isPresent());
        assertContainsAll(swingHtml.get(), "Open / Print Diagram", PlantUmlRenderer.DIAGRAM_OPEN_PREFIX);

        Matcher m = Pattern.compile("diagram-open:([0-9a-f]{16})").matcher(swingHtml.get());
        assertTrue(m.find(), "Expected canonical 16-hex diagram-open link");
        String diagramId = m.group(1);

        AtomicReference<Path> openedFile = new AtomicReference<>();
        HtmlExportService.setBrowserLauncherForTesting(openedFile::set);
        assertTrue(HtmlExportService.handleDiagramHyperlink(PlantUmlRenderer.DIAGRAM_OPEN_PREFIX + diagramId));
        long deadline = System.currentTimeMillis() + 5000L;
        while (openedFile.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        assertNotNull(openedFile.get(), "Expected browser launcher to receive exported diagram HTML file");
        String diagramFileHtml = Files.readString(openedFile.get());
        assertContainsAll(diagramFileHtml, "<!DOCTYPE html>", "Diagram Export", "<svg", "window.print()");
        assertFalse(HtmlExportService.handleDiagramHyperlink("https://example.com"));
    }
}
