package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

class MemoryServiceTest {

    @TempDir Path tempDir;

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

        EditorResult globalRes =
                memoryService.updateGlobalMemory("# Global Prefs\n- Prefer Java records");
        assertTrue(globalRes.success());
        assertEquals("# Global Prefs\n- Prefer Java records", memoryService.readGlobalMemory());

        EditorResult projRes =
                memoryService.updateProjectMemory("# Project Rules\n- Swing HTML 3.2 only");
        assertTrue(projRes.success());
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

        EditorResult saveGlobal =
                memoryService.saveGlobalNote(
                        "Java Style.md",
                        "Java Style Guide",
                        "java, style",
                        "Always use immutable records.");
        assertTrue(saveGlobal.success());

        String globalContent = memoryService.readGlobalNote("java-style");
        assertTrue(globalContent.startsWith("---\n"));
        assertTrue(globalContent.contains("id: ") && globalContent.contains("java-style"));
        assertTrue(globalContent.contains("scope: ") && globalContent.contains("global"));
        assertTrue(globalContent.contains("title: ") && globalContent.contains("Java Style Guide"));
        assertTrue(globalContent.contains("tags: ") && globalContent.contains("java, style"));
        assertTrue(globalContent.contains("updatedAt: "));
        assertTrue(globalContent.contains("# Java Style Guide"));
        assertTrue(globalContent.contains("Always use immutable records."));

        List<String> globalList = memoryService.listGlobalNotes();
        assertEquals(1, globalList.size());
        assertTrue(globalList.get(0).contains("java-style.md - "));
        assertTrue(globalList.get(0).contains("Java Style Guide"));
        assertTrue(globalList.get(0).contains("[java, style] ("));

        EditorResult saveProj =
                memoryService.saveProjectNote(
                        "swing-html-tables",
                        "Swing HTML 3.2 Table Layout Rules",
                        "ui, swing, html",
                        "Use old-school HTML tables for layout.");
        assertTrue(saveProj.success());

        String projContent = memoryService.readProjectNote("swing-html-tables.md");
        assertTrue(projContent.contains("swing-html-tables"));
        assertTrue(projContent.contains("project"));
        assertTrue(projContent.contains("Swing HTML 3.2 Table Layout Rules"));
        assertTrue(projContent.contains("ui, swing, html"));

        List<String> projList = memoryService.listProjectNotes();
        assertEquals(1, projList.size());
        assertTrue(projList.get(0).contains("swing-html-tables.md - "));
        assertTrue(projList.get(0).contains("Swing HTML 3.2 Table Layout Rules"));
        assertTrue(projList.get(0).contains("[ui, swing, html] ("));

        EditorResult delGlobal = memoryService.deleteGlobalNote("java-style");
        assertTrue(delGlobal.success());
        assertEquals("", memoryService.readGlobalNote("java-style"));
        assertTrue(memoryService.listGlobalNotes().isEmpty());
        assertFalse(memoryService.deleteGlobalNote("java-style").success());

        EditorResult delProj = memoryService.deleteProjectNote("swing-html-tables");
        assertTrue(delProj.success());
        assertEquals("", memoryService.readProjectNote("swing-html-tables"));
        assertTrue(memoryService.listProjectNotes().isEmpty());
    }

    @Test
    void testSlugSanitizationAndPathTraversalRejection() {
        assertEquals("swing-html-tables", MemoryService.sanitizeSlug("Swing HTML Tables.md"));
        assertEquals(
                "a".repeat(MemoryService.MAX_SLUG_LENGTH),
                MemoryService.sanitizeSlug("a".repeat(80)));

        String backslashPath = "notes" + (char) 92 + "sub";
        for (String invalid :
                new String[] {
                    null,
                    "",
                    "   ",
                    "../secret",
                    "../../etc/passwd",
                    "notes/sub",
                    backslashPath,
                    "---"
                }) {
            assertThrows(IllegalArgumentException.class, () -> MemoryService.sanitizeSlug(invalid));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> memoryService.saveGlobalNote(invalid, "Title", "tag", "body"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> memoryService.saveProjectNote(invalid, "Title", "tag", "body"));
            assertThrows(
                    IllegalArgumentException.class, () -> memoryService.readGlobalNote(invalid));
            assertThrows(
                    IllegalArgumentException.class, () -> memoryService.readProjectNote(invalid));
            assertThrows(
                    IllegalArgumentException.class, () -> memoryService.deleteGlobalNote(invalid));
            assertThrows(
                    IllegalArgumentException.class, () -> memoryService.deleteProjectNote(invalid));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> memoryService.readArchivedPlan("../secret.md"));
        assertThrows(
                IllegalArgumentException.class,
                () -> memoryService.readArchivedTopic("../secret.md"));
    }

    @Test
    void testArchiveListAndReadTopics() {
        assertTrue(memoryService.listArchivedTopics().isEmpty());
        assertNull(memoryService.archiveTopicConversation("Title", "Alice", null, List.of()));

        var entries =
                List.of(
                        new org.roxycode.app.ui.MarkdownPane.ChatTranscriptEntry(
                                "user", "How does MemoryService search work?", "15:28:10"),
                        new org.roxycode.app.ui.MarkdownPane.ChatTranscriptEntry(
                                "ai", "It executes rg across memory and archives.", "15:28:18"));

        String file1 =
                memoryService.archiveTopicConversation(
                        "Memory Service Architecture", "Alice", "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture.md", file1);

        String file2 =
                memoryService.archiveTopicConversation(
                        "Memory Service Architecture", "Alice", "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture-2.md", file2);

        List<String> topics = memoryService.listArchivedTopics();
        assertEquals(2, topics.size());
        assertEquals(
                "20250514-153000-memory-service-architecture.md: Memory Service Architecture",
                topics.get(0));
        assertEquals(
                "20250514-153000-memory-service-architecture-2.md: Memory Service Architecture",
                topics.get(1));

        String content = memoryService.readArchivedTopic(file1);
        assertTrue(
                content.contains(
                        """
                        type: "topic"
                        """
                                .strip()));
        assertTrue(
                content.contains(
                        """
                        title: "Memory Service Architecture"
                        """
                                .strip()));
        assertTrue(
                content.contains(
                        """
                        createdBy: "Alice"
                        """
                                .strip()));
        assertTrue(content.contains("messageCount: 2"));
        assertTrue(content.contains("# Topic: Memory Service Architecture"));
        assertTrue(content.contains("## Conversation Transcript"));
        assertTrue(content.contains("### User (15:28:10)\nHow does MemoryService search work?"));
        assertTrue(
                content.contains(
                        "### Roxy (15:28:18)\nIt executes rg across memory and archives."));
        assertEquals(
                content,
                memoryService.readArchivedTopic("20250514-153000-memory-service-architecture"));
    }

    @Test
    void testListAndReadArchivedPlans() throws IOException {
        assertTrue(memoryService.listArchivedPlans().isEmpty());

        Path archiveDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        String plan1 =
                """
                ---
                id: "20250513-100000-first-plan"
                title: "First Feature Plan"
                ---
                # Implementation Plan: First Feature Plan
                """;
        String plan2 =
                """
                ---
                id: "20250514-120000-second-plan"
                title: "Second Feature Plan"
                ---
                # Implementation Plan: Second Feature Plan
                """;
        Files.writeString(
                archiveDir.resolve("20250513-100000-first-plan.md"), plan1, StandardCharsets.UTF_8);
        Files.writeString(
                archiveDir.resolve("20250514-120000-second-plan.md"),
                plan2,
                StandardCharsets.UTF_8);

        List<String> plans = memoryService.listArchivedPlans();
        assertEquals(2, plans.size());
        assertEquals("20250514-120000-second-plan.md: Second Feature Plan", plans.get(0));
        assertEquals("20250513-100000-first-plan.md: First Feature Plan", plans.get(1));

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
        memoryService.saveProjectNote(
                "bar", "Project Bar", "project", "UniqueCrossScopeToken beta");

        Path archiveDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        String bazPlan =
                """
                ---
                title: "Baz Plan"
                ---
                UniqueCrossScopeToken gamma
                """;
        Path bazFile = archiveDir.resolve("20250514-baz.md");
        Files.writeString(bazFile, bazPlan, StandardCharsets.UTF_8);

        Path archiveTopicsDir = Files.createDirectories(projectDataDir.resolve("archive/topics"));
        Path topicFile = archiveTopicsDir.resolve("20250514-topic.md");
        Files.writeString(topicFile, "UniqueCrossScopeToken delta\n", StandardCharsets.UTF_8);

        Path globalNote = roxyHomeDir.resolve("memory/notes/foo.md").toAbsolutePath().normalize();
        Path projectNote =
                projectDataDir.resolve("memory/notes/bar.md").toAbsolutePath().normalize();
        Path archivePlan = bazFile.toAbsolutePath().normalize();
        Path archiveTopic = topicFile.toAbsolutePath().normalize();

        String simulatedRgStdout =
                globalNote
                        + ":12:UniqueCrossScopeToken alpha\n"
                        + projectNote
                        + ":5:UniqueCrossScopeToken beta\n"
                        + archivePlan
                        + ":8:UniqueCrossScopeToken gamma\n"
                        + archiveTopic
                        + ":15:UniqueCrossScopeToken delta\n";

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, simulatedRgStdout, "", false));

            EditorResult searchRes = memoryService.search("UniqueCrossScopeToken");
            assertTrue(searchRes.success());
            String output = searchRes.content();
            assertTrue(
                    output.contains("[GLOBAL] notes/foo.md:12:UniqueCrossScopeToken alpha"),
                    "Output was: " + output);
            assertTrue(
                    output.contains("[PROJECT] notes/bar.md:5:UniqueCrossScopeToken beta"),
                    "Output was: " + output);
            assertTrue(
                    output.contains(
                            "[ARCHIVE] plans/20250514-baz.md:8:UniqueCrossScopeToken gamma"),
                    "Output was: " + output);
            assertTrue(
                    output.contains(
                            "[ARCHIVE] topics/20250514-topic.md:15:UniqueCrossScopeToken delta"),
                    "Output was: " + output);

            EditorResult caseInsensitiveRes =
                    memoryService.searchIgnoreCase("uniquecrossscopetoken");
            assertTrue(caseInsensitiveRes.success());
            assertTrue(caseInsensitiveRes.content().contains("[GLOBAL] notes/foo.md:12:"));
            assertTrue(caseInsensitiveRes.content().contains("[PROJECT] notes/bar.md:5:"));
            assertTrue(caseInsensitiveRes.content().contains("[ARCHIVE] plans/20250514-baz.md:8:"));

            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
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

        String manyLines = "line\n".repeat(250);
        String capped = MemoryService.capPinnedMemoryForPrompt(manyLines);
        assertTrue(capped.contains("truncated at 200 lines / 8 KB limit"));
    }

    @Test
    void testStripFrontmatter() {
        assertEquals("", MemoryService.stripFrontmatter(null));
        assertEquals("", MemoryService.stripFrontmatter(""));
        assertEquals("# Heading\nBody", MemoryService.stripFrontmatter("# Heading\nBody"));
        assertEquals(
                "---\nunclosed frontmatter",
                MemoryService.stripFrontmatter("---\nunclosed frontmatter"));

        String doc =
                """
                ---
                id: "20250514-topic"
                title: "Test Topic"
                ---

                # Topic: Test Topic

                Body text here.
                """;
        String stripped = MemoryService.stripFrontmatter(doc);
        assertFalse(stripped.contains("id: "));
        assertTrue(stripped.startsWith("# Topic: Test Topic"));
        assertTrue(stripped.contains("Body text here."));
    }

    @Test
    void testArchiveSummariesAndScopedSearch() throws IOException {
        assertTrue(memoryService.listArchivedTopicSummaries().isEmpty());
        assertTrue(memoryService.listArchivedPlanSummaries().isEmpty());

        memoryService.archiveTopicConversation(
                "First Q&A Topic",
                "Alice",
                "2025-05-13T10:00:00Z",
                List.of(new ChatTranscriptEntry("user", "Question about caching", "10:00:00")));
        memoryService.archiveTopicConversation(
                "Second Q&A Topic",
                "Bob",
                "2025-05-14T15:30:00Z",
                List.of(
                        new ChatTranscriptEntry("user", "How does RipGrep work?", "15:29:00"),
                        new ChatTranscriptEntry("ai", "RipGrep searches fast.", "15:29:10")));

        List<MemoryService.ArchiveEntrySummary> topicSummaries =
                memoryService.listArchivedTopicSummaries();
        assertEquals(2, topicSummaries.size());
        assertEquals("Second Q&A Topic", topicSummaries.get(0).title());
        assertEquals("Bob", topicSummaries.get(0).createdBy());
        assertEquals(2, topicSummaries.get(0).messageCount());
        assertEquals("First Q&A Topic", topicSummaries.get(1).title());
        assertEquals("Alice", topicSummaries.get(1).createdBy());
        assertEquals(1, topicSummaries.get(1).messageCount());

        Path plansDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        String planDoc =
                """
                ---
                id: "20250514-120000-feature-plan"
                title: "Archive Browser Feature"
                completedBy: "Charlie"
                completedOn: "2025-05-14T12:00:00Z"
                ---

                # Implementation Plan: Archive Browser Feature
                UniqueBodyMatchToken in plan steps
                """;
        Path planFile = plansDir.resolve("20250514-120000-feature-plan.md");
        Files.writeString(planFile, planDoc, StandardCharsets.UTF_8);
        Path fallbackPlanFile = plansDir.resolve("20250512-090000-raw-plan.md");
        Files.writeString(
                fallbackPlanFile,
                """
                # Raw Plan Without Frontmatter
                """,
                StandardCharsets.UTF_8);

        List<MemoryService.ArchiveEntrySummary> planSummaries =
                memoryService.listArchivedPlanSummaries();
        assertEquals(2, planSummaries.size());
        assertEquals("Archive Browser Feature", planSummaries.get(0).title());
        assertEquals("Charlie", planSummaries.get(0).createdBy());
        assertEquals("20250512-090000-raw-plan", planSummaries.get(1).title());

        assertEquals(2, memoryService.searchArchivedTopics("").size());
        assertEquals(2, memoryService.searchArchivedPlans("   ").size());

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(1, "", "", false));

            List<MemoryService.ArchiveEntrySummary> metaMatched =
                    memoryService.searchArchivedTopics("alice");
            assertEquals(1, metaMatched.size());
            assertEquals("First Q&A Topic", metaMatched.get(0).title());

            String rgOut =
                    planFile.toAbsolutePath().normalize() + ":9:UniqueBodyMatchToken in plan steps";
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, rgOut, "", false));

            List<MemoryService.ArchiveEntrySummary> planMatched =
                    memoryService.searchArchivedPlans("UniqueBodyMatchToken");
            assertEquals(1, planMatched.size());
            assertEquals("Archive Browser Feature", planMatched.get(0).title());
            assertEquals("UniqueBodyMatchToken in plan steps", planMatched.get(0).matchSnippet());
        }
    }
}
