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
        }

        assertThrows(IllegalArgumentException.class,
                () -> memoryService.saveGlobalNote("../secret", "Title", "tag", "body"));
        assertThrows(IllegalArgumentException.class, () -> memoryService.readProjectNote("../secret"));
        assertThrows(IllegalArgumentException.class, () -> memoryService.deleteGlobalNote("../secret"));
        assertThrows(IllegalArgumentException.class, () -> memoryService.readArchivedPlan("../secret.md"));
        assertThrows(IllegalArgumentException.class, () -> memoryService.readArchivedTopic("../secret.md"));
    }

    @Test
    void testArchiveTopicsAndPlansCrudSummariesAndSearch() throws IOException {
        assertTrue(memoryService.listArchivedTopics().isEmpty());
        assertTrue(memoryService.listArchivedPlans().isEmpty());
        assertTrue(memoryService.listArchivedTopicSummaries().isEmpty());
        assertTrue(memoryService.listArchivedPlanSummaries().isEmpty());
        assertNull(memoryService.archiveTopicConversation("Title", "Alice", null, List.of()));

        List<ChatTranscriptEntry> entries = List.of(
                new ChatTranscriptEntry("user", "How does MemoryService search work?", "15:28:10"),
                new ChatTranscriptEntry("ai", "It executes rg across memory and archives.", "15:28:18"));

        String file1 = memoryService.archiveTopicConversation("Memory Service Architecture", "Alice",
                "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture.md", file1);

        String file2 = memoryService.archiveTopicConversation("Memory Service Architecture", "Bob",
                "2025-05-14T15:30:00Z", List.of(new ChatTranscriptEntry("user", "Question about caching", "10:00:00")));
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

        List<MemoryService.ArchiveEntrySummary> topicSummaries = memoryService.listArchivedTopicSummaries();
        assertEquals(2, topicSummaries.size());
        assertEquals("Alice", topicSummaries.get(0).createdBy());
        assertEquals(2, topicSummaries.get(0).messageCount());
        assertEquals("Bob", topicSummaries.get(1).createdBy());
        assertEquals(1, topicSummaries.get(1).messageCount());

        Path plansDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        Path fallbackPlanFile = plansDir.resolve("20250512-090000-raw-plan.md");
        Files.writeString(fallbackPlanFile, "# Raw Plan Without Frontmatter\n", StandardCharsets.UTF_8);

        Path planFile = plansDir.resolve("20250514-120000-feature-plan.md");
        String plan2 = """
                ---
                id: "20250514-120000-feature-plan"
                title: "Archive Browser Feature"
                completedBy: "Charlie"
                completedOn: "2025-05-14T12:00:00Z"
                ---

                # Implementation Plan: Archive Browser Feature
                UniqueBodyMatchToken in plan steps
                """;
        Files.writeString(planFile, plan2, StandardCharsets.UTF_8);

        assertEquals(List.of("20250514-120000-feature-plan.md: Archive Browser Feature",
                "20250512-090000-raw-plan.md: 20250512-090000-raw-plan"), memoryService.listArchivedPlans());
        assertEquals(plan2, memoryService.readArchivedPlan("20250514-120000-feature-plan.md"));
        assertEquals(plan2, memoryService.readArchivedPlan("20250514-120000-feature-plan"));

        List<MemoryService.ArchiveEntrySummary> planSummaries = memoryService.listArchivedPlanSummaries();
        assertEquals(2, planSummaries.size());
        assertEquals("Archive Browser Feature", planSummaries.get(0).title());
        assertEquals("Charlie", planSummaries.get(0).createdBy());
        assertEquals("20250512-090000-raw-plan", planSummaries.get(1).title());

        assertEquals(2, memoryService.searchArchivedTopics("").size());
        assertEquals(2, memoryService.searchArchivedPlans("   ").size());

        List<MemoryService.ArchiveEntrySummary> metaMatched = memoryService.searchArchivedTopics("alice");
        assertEquals(1, metaMatched.size());
        assertEquals(file1, metaMatched.get(0).filename());

        List<MemoryService.ArchiveEntrySummary> planMatched = memoryService.searchArchivedPlans("UniqueBodyMatchToken");
        assertEquals(1, planMatched.size());
        assertEquals("Archive Browser Feature", planMatched.get(0).title());
        assertEquals("UniqueBodyMatchToken in plan steps", planMatched.get(0).matchSnippet());

        List<MemoryService.ArchiveEntrySummary> fmKeyIgnored = memoryService.searchArchivedPlans("Charlie");
        assertEquals(1, fmKeyIgnored.size());
        assertEquals("", fmKeyIgnored.get(0).matchSnippet());

        assertTrue(FrontmatterUtils.parseFrontmatter(fallbackPlanFile).isEmpty());
        assertTrue(FrontmatterUtils.parseFrontmatter((Path) null).isEmpty());

        Path unclosedFrontmatterFile = plansDir.resolve("20250511-080000-unclosed.md");
        Files.writeString(unclosedFrontmatterFile, """
                ---
                title: 'Unclosed "Quote" Plan'
                created_by: "Dave"
                # Missing closing delimiter
                """, StandardCharsets.UTF_8);
        // Flexmark requires a closing --- to recognize a YamlFrontMatterBlock
        assertTrue(FrontmatterUtils.parseFrontmatter(unclosedFrontmatterFile).isEmpty());
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
            assertContainsAll(searchRes.content(), "foo.md:12:UniqueCrossScopeToken alpha",
                    "bar.md:5:UniqueCrossScopeToken beta", "20250514-baz.md:8:UniqueCrossScopeToken gamma",
                    "20250514-topic.md:15:UniqueCrossScopeToken delta");

            EditorResult caseInsensitiveRes = memoryService.searchIgnoreCase("uniquecrossscopetoken");
            assertTrue(caseInsensitiveRes.success());
            assertContainsAll(caseInsensitiveRes.content(), "foo.md:12:", "bar.md:5:", "20250514-baz.md:8:");
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(1, "", "", false));
            EditorResult noMatchRes = memoryService.search("DEFINITELY_NOT_FOUND_99999");
            assertTrue(noMatchRes.success());
            assertEquals("", noMatchRes.content());
        }
    }

    @Test
    void testPromptCappingFrontmatterStrippingAndTranscriptExcerpt() {
        assertEquals("", MemoryService.capPinnedMemoryForPrompt(null));
        assertEquals("short", MemoryService.capPinnedMemoryForPrompt("short"));
        assertTrue(MemoryService.capPinnedMemoryForPrompt("line\n".repeat(250))
                .contains("truncated at 200 lines / 8 KB limit"));

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
