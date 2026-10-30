package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.MarkdownPane.ChatTranscriptEntry;
import org.roxycode.app.util.FrontmatterUtils;

class MemoryServiceTest {

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
        memoryService = new MemoryService(settingsService, projectService);
    }

    @Test
    void testGlobalAndProjectMemoryIndexReadAndUpdate() {
        assertEquals("", memoryService.readGlobalMemory());
        assertEquals("", memoryService.readProjectMemory());

        assertTrue(memoryService.updateGlobalMemory("# Global Prefs\n- Prefer Java records").success());
        assertEquals("# Global Prefs\n- Prefer Java records", memoryService.readGlobalMemory());

        assertTrue(memoryService.updateProjectMemory("# Project Rules\n- Swing HTML 3.2 only").success());
        assertEquals("# Project Rules\n- Swing HTML 3.2 only", memoryService.readProjectMemory());

        when(projectService.hasActiveProject()).thenReturn(false);
        assertEquals("", memoryService.readProjectMemory());
        EditorResult noProjRes = memoryService.updateProjectMemory("content");
        assertFalse(noProjRes.success());
        assertEquals("No active project", noProjRes.errorHint());
    }

    @Test
    void testSaveReadListAndDeleteTopicNotesWithFrontmatter() {
        assertTrue(memoryService.listGlobalNotes().isEmpty());
        assertTrue(memoryService.listProjectNotes().isEmpty());

        assertTrue(memoryService
                .saveGlobalNote("Java Style.md", "Java Style Guide", "java, style", "Always use immutable records.")
                .success());

        String globalContent = memoryService.readGlobalNote("java-style");
        assertTrue(globalContent.startsWith("---\n"));
        assertContainsAll(globalContent, "id: ", "java-style", "scope: ", "global", "title: ", "Java Style Guide",
                "tags: ", "java, style", "updatedAt: ", "# Java Style Guide", "Always use immutable records.");

        List<String> globalList = memoryService.listGlobalNotes();
        assertEquals(1, globalList.size());
        assertContainsAll(globalList.get(0), "java-style.md - ", "Java Style Guide", "[java, style] (");

        assertTrue(memoryService.saveProjectNote("swing-html-tables", "Swing HTML 3.2 Table Layout Rules",
                "ui, swing, html", "Use old-school HTML tables for layout.").success());

        assertContainsAll(memoryService.readProjectNote("swing-html-tables.md"), "swing-html-tables", "project",
                "Swing HTML 3.2 Table Layout Rules", "ui, swing, html");

        List<String> projList = memoryService.listProjectNotes();
        assertEquals(1, projList.size());
        assertContainsAll(projList.get(0), "swing-html-tables.md - ", "Swing HTML 3.2 Table Layout Rules",
                "[ui, swing, html] (");

        assertTrue(memoryService.deleteGlobalNote("java-style").success());
        assertEquals("", memoryService.readGlobalNote("java-style"));
        assertTrue(memoryService.listGlobalNotes().isEmpty());
        assertFalse(memoryService.deleteGlobalNote("java-style").success());

        assertTrue(memoryService.deleteProjectNote("swing-html-tables").success());
        assertEquals("", memoryService.readProjectNote("swing-html-tables"));
        assertTrue(memoryService.listProjectNotes().isEmpty());
    }

    @Test
    void testSlugSanitizationAndPathTraversalRejection() {
        assertEquals("swing-html-tables", MemoryService.sanitizeSlug("Swing HTML Tables.md"));
        assertEquals("a".repeat(MemoryService.MAX_SLUG_LENGTH), MemoryService.sanitizeSlug("a".repeat(80)));

        String backslashPath = "notes" + (char) 92 + "sub";
        for (String invalid : new String[]{null, "", "   ", "../secret", "../../etc/passwd", "notes/sub", backslashPath,
                "---"}) {
            assertThrows(IllegalArgumentException.class, () -> MemoryService.sanitizeSlug(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> memoryService.saveGlobalNote(invalid, "Title", "tag", "body"));
            assertThrows(IllegalArgumentException.class,
                    () -> memoryService.saveProjectNote(invalid, "Title", "tag", "body"));
            assertThrows(IllegalArgumentException.class, () -> memoryService.readGlobalNote(invalid));
            assertThrows(IllegalArgumentException.class, () -> memoryService.readProjectNote(invalid));
            assertThrows(IllegalArgumentException.class, () -> memoryService.deleteGlobalNote(invalid));
            assertThrows(IllegalArgumentException.class, () -> memoryService.deleteProjectNote(invalid));
        }

        assertThrows(IllegalArgumentException.class, () -> memoryService.readArchivedPlan("../secret.md"));
        assertThrows(IllegalArgumentException.class, () -> memoryService.readArchivedTopic("../secret.md"));
    }

    @Test
    void testArchiveListAndReadTopics() {
        assertTrue(memoryService.listArchivedTopics().isEmpty());
        assertNull(memoryService.archiveTopicConversation("Title", "Alice", null, List.of()));

        var entries = List.of(new ChatTranscriptEntry("user", "How does MemoryService search work?", "15:28:10"),
                new ChatTranscriptEntry("ai", "It executes rg across memory and archives.", "15:28:18"));

        String file1 = memoryService.archiveTopicConversation("Memory Service Architecture", "Alice",
                "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture.md", file1);

        String file2 = memoryService.archiveTopicConversation("Memory Service Architecture", "Alice",
                "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture-2.md", file2);

        assertEquals(
                List.of("20250514-153000-memory-service-architecture.md: Memory Service Architecture",
                        "20250514-153000-memory-service-architecture-2.md: Memory Service Architecture"),
                memoryService.listArchivedTopics());

        String content = memoryService.readArchivedTopic(file1);
        assertContainsAll(content, "type: ", "topic", "title: ", "Memory Service Architecture", "createdBy: ", "Alice",
                "messageCount: 2", "# Topic: Memory Service Architecture", "## Conversation Transcript",
                "### User (15:28:10)\nHow does MemoryService search work?",
                "### Roxy (15:28:18)\nIt executes rg across memory and archives.");
        assertEquals(content, memoryService.readArchivedTopic("20250514-153000-memory-service-architecture"));
    }

    @Test
    void testListAndReadArchivedPlans() throws IOException {
        assertTrue(memoryService.listArchivedPlans().isEmpty());

        Path archiveDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        String plan1 = """
                ---
                id: "20250513-100000-first-plan"
                title: "First Feature Plan"
                ---
                # Implementation Plan: First Feature Plan
                """;
        String plan2 = """
                ---
                id: "20250514-120000-second-plan"
                title: "Second Feature Plan"
                ---
                # Implementation Plan: Second Feature Plan
                """;
        Files.writeString(archiveDir.resolve("20250513-100000-first-plan.md"), plan1, StandardCharsets.UTF_8);
        Files.writeString(archiveDir.resolve("20250514-120000-second-plan.md"), plan2, StandardCharsets.UTF_8);

        assertEquals(List.of("20250514-120000-second-plan.md: Second Feature Plan",
                "20250513-100000-first-plan.md: First Feature Plan"), memoryService.listArchivedPlans());
        assertEquals(plan2, memoryService.readArchivedPlan("20250514-120000-second-plan.md"));
        assertEquals(plan2, memoryService.readArchivedPlan("20250514-120000-second-plan"));
    }

    @Test
    void testUnifiedRipgrepSearchAndSearchIgnoreCase() throws IOException {
        EditorResult emptySearch = memoryService.search("nonexistent");
        assertTrue(emptySearch.success());
        assertEquals("", emptySearch.content());
        assertFalse(memoryService.search("").success());

        memoryService.saveGlobalNote("foo", "Global Foo", "global", "UniqueCrossScopeToken alpha");
        memoryService.saveProjectNote("bar", "Project Bar", "project", "UniqueCrossScopeToken beta");

        Path archiveDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        Path bazFile = archiveDir.resolve("20250514-baz.md");
        Files.writeString(bazFile, "---\ntitle: 'Baz Plan'\n---\nUniqueCrossScopeToken gamma\n",
                StandardCharsets.UTF_8);

        Path archiveTopicsDir = Files.createDirectories(projectDataDir.resolve("archive/topics"));
        Path topicFile = archiveTopicsDir.resolve("20250514-topic.md");
        Files.writeString(topicFile, "UniqueCrossScopeToken delta\n", StandardCharsets.UTF_8);

        String simulatedRgStdout = roxyHomeDir.resolve("memory/notes/foo.md").toAbsolutePath().normalize()
                + ":12:UniqueCrossScopeToken alpha\n"
                + projectDataDir.resolve("memory/notes/bar.md").toAbsolutePath().normalize()
                + ":5:UniqueCrossScopeToken beta\n" + bazFile.toAbsolutePath().normalize()
                + ":8:UniqueCrossScopeToken gamma\n" + topicFile.toAbsolutePath().normalize()
                + ":15:UniqueCrossScopeToken delta\n";

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, simulatedRgStdout, "", false));

            EditorResult searchRes = memoryService.search("UniqueCrossScopeToken");
            assertTrue(searchRes.success());
            assertContainsAll(searchRes.content(), "[GLOBAL] notes/foo.md:12:UniqueCrossScopeToken alpha",
                    "[PROJECT] notes/bar.md:5:UniqueCrossScopeToken beta",
                    "[ARCHIVE] plans/20250514-baz.md:8:UniqueCrossScopeToken gamma",
                    "[ARCHIVE] topics/20250514-topic.md:15:UniqueCrossScopeToken delta");

            EditorResult caseInsensitiveRes = memoryService.searchIgnoreCase("uniquecrossscopetoken");
            assertTrue(caseInsensitiveRes.success());
            assertContainsAll(caseInsensitiveRes.content(), "[GLOBAL] notes/foo.md:12:", "[PROJECT] notes/bar.md:5:",
                    "[ARCHIVE] plans/20250514-baz.md:8:");

            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(1, "", "", false));
            EditorResult noMatchRes = memoryService.search("DEFINITELY_NOT_FOUND_99999");
            assertTrue(noMatchRes.success());
            assertEquals("", noMatchRes.content());
        }
    }

    @Test
    void testCapPinnedMemoryForPrompt() {
        assertEquals("", MemoryService.capPinnedMemoryForPrompt(null));
        assertEquals("short", MemoryService.capPinnedMemoryForPrompt("short"));
        assertTrue(MemoryService.capPinnedMemoryForPrompt("line\n".repeat(250))
                .contains("truncated at 200 lines / 8 KB limit"));
    }

    @Test
    void testStripFrontmatter() {
        assertEquals("", FrontmatterUtils.stripFrontmatter(null));
        assertEquals("", FrontmatterUtils.stripFrontmatter(""));
        assertEquals("# Heading\nBody", FrontmatterUtils.stripFrontmatter("# Heading\nBody"));
        assertEquals("---\nunclosed frontmatter", FrontmatterUtils.stripFrontmatter("---\nunclosed frontmatter"));

        String stripped = FrontmatterUtils.stripFrontmatter("""
                ---
                id: "20250514-topic"
                title: "Test Topic"
                ---

                # Topic: Test Topic

                Body text here.
                """);
        assertFalse(stripped.contains("id: "));
        assertTrue(stripped.startsWith("# Topic: Test Topic"));
        assertTrue(stripped.contains("Body text here."));
    }

    @Test
    void testArchiveSummariesAndScopedSearch() throws IOException {
        assertTrue(memoryService.listArchivedTopicSummaries().isEmpty());
        assertTrue(memoryService.listArchivedPlanSummaries().isEmpty());

        memoryService.archiveTopicConversation("First Q&A Topic", "Alice", "2025-05-13T10:00:00Z",
                List.of(new ChatTranscriptEntry("user", "Question about caching", "10:00:00")));
        memoryService.archiveTopicConversation("Second Q&A Topic", "Bob", "2025-05-14T15:30:00Z",
                List.of(new ChatTranscriptEntry("user", "How does RipGrep work?", "15:29:00"),
                        new ChatTranscriptEntry("ai", "RipGrep searches fast.", "15:29:10")));

        List<MemoryService.ArchiveEntrySummary> topicSummaries = memoryService.listArchivedTopicSummaries();
        assertEquals(2, topicSummaries.size());
        assertEquals("Second Q&A Topic", topicSummaries.get(0).title());
        assertEquals("Bob", topicSummaries.get(0).createdBy());
        assertEquals(2, topicSummaries.get(0).messageCount());
        assertEquals("First Q&A Topic", topicSummaries.get(1).title());
        assertEquals("Alice", topicSummaries.get(1).createdBy());
        assertEquals(1, topicSummaries.get(1).messageCount());

        Path plansDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        Path planFile = plansDir.resolve("20250514-120000-feature-plan.md");
        Files.writeString(planFile, """
                ---
                id: "20250514-120000-feature-plan"
                title: "Archive Browser Feature"
                completedBy: "Charlie"
                completedOn: "2025-05-14T12:00:00Z"
                ---

                # Implementation Plan: Archive Browser Feature
                UniqueBodyMatchToken in plan steps
                """, StandardCharsets.UTF_8);
        Path fallbackPlanFile = plansDir.resolve("20250512-090000-raw-plan.md");
        Files.writeString(fallbackPlanFile, "# Raw Plan Without Frontmatter\n", StandardCharsets.UTF_8);

        List<MemoryService.ArchiveEntrySummary> planSummaries = memoryService.listArchivedPlanSummaries();
        assertEquals(2, planSummaries.size());
        assertEquals("Archive Browser Feature", planSummaries.get(0).title());
        assertEquals("Charlie", planSummaries.get(0).createdBy());
        assertEquals("20250512-090000-raw-plan", planSummaries.get(1).title());

        assertEquals(2, memoryService.searchArchivedTopics("").size());
        assertEquals(2, memoryService.searchArchivedPlans("   ").size());

        Path planWithSnippet = plansDir.resolve("20250515-100000-extra.md");
        Files.writeString(planWithSnippet, """
                ---
                title: "Extra Plan"
                created_by: "ExtraUser"
                timestamp: "2025-05-15T10:00:00Z"
                ---

                # Plan
                BodyExampleMatchLine in body after frontmatter
                """, StandardCharsets.UTF_8);

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(1, "", "", false));
            List<MemoryService.ArchiveEntrySummary> metaMatched = memoryService.searchArchivedTopics("alice");
            assertEquals(1, metaMatched.size());
            assertEquals("First Q&A Topic", metaMatched.get(0).title());

            String planRgOut = planFile.toAbsolutePath().normalize() + ":UniqueBodyMatchToken in plan steps\n";
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, planRgOut, "", false));
            List<MemoryService.ArchiveEntrySummary> planMatched = memoryService
                    .searchArchivedPlans("UniqueBodyMatchToken");
            assertEquals(1, planMatched.size());
            assertEquals("Archive Browser Feature", planMatched.get(0).title());
            assertEquals("UniqueBodyMatchToken in plan steps", planMatched.get(0).matchSnippet());

            String extraRgOut = planWithSnippet.toAbsolutePath().normalize() + ":---\n"
                    + planWithSnippet.toAbsolutePath().normalize() + ":title: " + '"' + "Extra Plan" + '"' + "\n"
                    + planWithSnippet.toAbsolutePath().normalize() + ":created_by: " + '"' + "ExtraUser" + '"' + "\n"
                    + planWithSnippet.toAbsolutePath().normalize()
                    + ":  BodyExampleMatchLine in body after frontmatter  \n";
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, extraRgOut, "", false));
            List<MemoryService.ArchiveEntrySummary> bodyKeyMatched = memoryService
                    .searchArchivedPlans("BodyExampleMatchLine");
            assertEquals(1, bodyKeyMatched.size());
            assertEquals("BodyExampleMatchLine in body after frontmatter", bodyKeyMatched.get(0).matchSnippet());

            String fmOnlyRgOut = planWithSnippet.toAbsolutePath().normalize() + ":title: " + '"' + "Extra Plan" + '"'
                    + "\n";
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, fmOnlyRgOut, "", false));
            List<MemoryService.ArchiveEntrySummary> fmKeyIgnored = memoryService.searchArchivedPlans("Extra Plan");
            assertEquals(1, fmKeyIgnored.size());
            assertEquals("", fmKeyIgnored.get(0).matchSnippet());
        }

        assertEquals(6, FrontmatterUtils.findFrontmatterEndLine(planFile));
        assertEquals(0, FrontmatterUtils.findFrontmatterEndLine(fallbackPlanFile));
        assertEquals(0, FrontmatterUtils.findFrontmatterEndLine((Path) null));
        assertEquals(0, FrontmatterUtils.findFrontmatterEndLine((List<String>) null));
        assertTrue(FrontmatterUtils.parseFrontmatter(fallbackPlanFile).isEmpty());
        assertTrue(FrontmatterUtils.parseFrontmatter(null).isEmpty());

        Path unclosedFrontmatterFile = plansDir.resolve("20250511-080000-unclosed.md");
        Files.writeString(unclosedFrontmatterFile, """
                ---
                title: "Unclosed \\"Quote\\" Plan"
                created_by: "Dave"
                # Missing closing delimiter
                """, StandardCharsets.UTF_8);
        assertEquals(0, FrontmatterUtils.findFrontmatterEndLine(unclosedFrontmatterFile));
        assertEquals("Unclosed " + '"' + "Quote" + '"' + " Plan",
                FrontmatterUtils.parseFrontmatter(unclosedFrontmatterFile).get("title"));
    }

    @Test
    void testWindowsDriveLetterCaseAndColonHandlingInArchiveAndSearchOutput() {
        assertTrue(MemoryService.startsWithSlashPrefix("c:/Users/dev/.roxycode/archive/plans/plan.md:8:text",
                "C:/Users/dev/.roxycode/archive/plans/"));
        assertTrue(MemoryService.startsWithSlashPrefix("C:/Users/dev/.roxycode/archive/plans/plan.md:8:text",
                "c:/Users/dev/.roxycode/archive/plans/"));
        assertFalse(MemoryService.startsWithSlashPrefix("d:/Users/dev/.roxycode/archive/plans/plan.md:8:text",
                "C:/Users/dev/.roxycode/archive/plans/"));
    }

    @Test
    void testBuildTranscriptExcerpt() {
        assertEquals("", MemoryService.buildTranscriptExcerpt(null));
        assertEquals("", MemoryService.buildTranscriptExcerpt(List.of()));

        String longMsg = "x".repeat(600);
        List<ChatTranscriptEntry> entries = List.of(
                new ChatTranscriptEntry("user", "  How does repo map packing work?  ", "10:00:00"),
                new ChatTranscriptEntry("ai", longMsg, "10:00:01"), new ChatTranscriptEntry("user", null, "10:00:02"));
        String excerpt = MemoryService.buildTranscriptExcerpt(entries);
        String nl = String.valueOf((char) 10);
        assertTrue(excerpt
                .startsWith("User: How does repo map packing work?" + nl + "Roxy: " + "x".repeat(500) + "..." + nl));
        assertTrue(excerpt.endsWith(nl + "User: "));
    }
}
