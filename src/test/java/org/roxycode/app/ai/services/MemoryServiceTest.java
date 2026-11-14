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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.roxycode.app.ai.services.MemoryService.MemoryEntry;
import org.roxycode.app.ai.services.MemoryService.MemoryMetadata;
import org.roxycode.app.events.MemoryChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.model.ChatTranscriptEntry;
import org.roxycode.app.util.FrontmatterUtils;

class MemoryServiceTest {

    @TempDir
    Path tempDir;

    private SettingsService settingsService;
    private ProjectService projectService;
    private MemoryService memoryService;
    private Path roxyHomeDir;
    private Path projectDataDir;
    private List<MemoryChangedEvent> publishedEvents;

    @BeforeEach
    void setUp() throws IOException {
        roxyHomeDir = Files.createDirectories(tempDir.resolve("roxyhome"));
        projectDataDir = Files.createDirectories(tempDir.resolve("project-data"));
        settingsService = mock(SettingsService.class);
        projectService = mock(ProjectService.class);
        publishedEvents = new ArrayList<>();
        when(settingsService.getRoxyHomeDir()).thenReturn(roxyHomeDir);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
        memoryService = new MemoryService(settingsService, projectService, event -> {
            if (event instanceof MemoryChangedEvent memEvent) {
                publishedEvents.add(memEvent);
            }
        });
    }

    @Test
    void testUnifiedMemoryCrudFindEventsAndLegacyMigration() throws IOException {
        assertTrue(memoryService.put("global", "no-ternary", "Never use ternary operators.").success());
        assertTrue(memoryService
                .put("global", "Java Style.md", "Java Style Guide", List.of("java", "STYLE"), "Use immutable records.")
                .success());
        assertEquals(2, publishedEvents.size());
        assertTrue(publishedEvents.get(0).globalScope());
        assertTrue(Files.isRegularFile(roxyHomeDir.resolve("memory/memories.json")));

        MemoryEntry globalEntry = memoryService.read("global", "java-style");
        assertNotNull(globalEntry);
        assertEquals("java-style", globalEntry.id());
        assertEquals("Java Style Guide", globalEntry.summary());
        assertEquals(List.of("java", "style"), globalEntry.tags());
        assertEquals("Use immutable records.", globalEntry.details());
        assertFalse(globalEntry.updatedAt().isEmpty());

        assertTrue(memoryService
                .put("project", "swing-tables", "Swing Tables", List.of("ui", "swing"), "Use HTML tables.").success());
        assertEquals(3, publishedEvents.size());
        assertFalse(publishedEvents.get(2).globalScope());

        assertEquals(2, memoryService.list("global").size());
        assertEquals(1, memoryService.list("project").size());
        assertEquals(3, memoryService.listAll().size());

        List<MemoryMetadata> found = memoryService.find("immutable");
        assertEquals(1, found.size());
        assertEquals("java-style", found.get(0).id());
        assertTrue(found.get(0).hasDetails());
        assertEquals(1, memoryService.find("project", "swing").size());

        assertTrue(memoryService.delete("global", "java-style").success());
        assertNull(memoryService.read("global", "java-style"));
        assertTrue(memoryService.delete("project", "swing-tables").success());
        assertFalse(memoryService.delete("project", "swing-tables").success());
        assertEquals(5, publishedEvents.size());

        for (String invalid : new String[]{null, "", "   ", "../secret", "notes/sub", "---"}) {
            assertThrows(IllegalArgumentException.class, () -> MemoryService.sanitizeSlug(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> memoryService.list("invalid"));
        assertThrows(IllegalArgumentException.class, () -> memoryService.readArchivedPlan("../secret.md"));

        when(projectService.hasActiveProject()).thenReturn(false);
        assertFalse(memoryService.put("project", "rule-1", "Summary").success());
        assertTrue(memoryService.list("project").isEmpty());

        // Legacy migration from MEMORY.md + notes/*.md when memories.json does not exist yet
        Path legacyProjectDir = Files.createDirectories(tempDir.resolve("legacy-project"));
        Path legacyMemDir = Files.createDirectories(legacyProjectDir.resolve("memory"));
        Path legacyNotesDir = Files.createDirectories(legacyMemDir.resolve("notes"));
        Files.writeString(legacyMemDir.resolve("MEMORY.md"), """
                # Project Memory
                - **Pragmatic Testing**: Only test meaningful business logic.
                - Never use ThreadLocals in Swing panels.
                """, StandardCharsets.UTF_8);
        Files.writeString(legacyNotesDir.resolve("caffeine-cache.md"), """
                ---
                title: "Async Caching with Caffeine"
                tags: "caching, async"
                updatedAt: "2026-10-30T23:25:46Z"
                ---

                # Async Caching with Caffeine

                Use Caffeine AsyncCache with virtual threads.
                """, StandardCharsets.UTF_8);

        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> legacyProjectDir.resolve(inv.getArgument(0, String.class)));
        List<MemoryEntry> migrated = memoryService.readAll("project");
        assertEquals(3, migrated.size());
        assertTrue(Files.isRegularFile(legacyMemDir.resolve("memories.json")));
        MemoryEntry migratedNote = memoryService.read("project", "caffeine-cache");
        assertNotNull(migratedNote);
        assertEquals("Async Caching with Caffeine", migratedNote.summary());
        assertEquals(List.of("caching", "async"), migratedNote.tags());
        assertEquals("Use Caffeine AsyncCache with virtual threads.", migratedNote.details());
    }

    @Test
    void testArchiveTopicsAndPlansCrudSummariesAndSearch() throws IOException {
        List<ChatTranscriptEntry> entries = List.of(
                new ChatTranscriptEntry("user", "How does MemoryService search work?", "15:28:10"),
                new ChatTranscriptEntry("ai", "It executes rg across memory and archives.", "15:28:18"));

        String file1 = memoryService.archiveTopicConversation("Memory Service Architecture", "Alice",
                "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture.md", file1);
        String file2 = memoryService.archiveTopicConversation("Memory Service Architecture", "Bob",
                "2025-05-14T15:30:00Z", entries);
        assertEquals("20250514-153000-memory-service-architecture-2.md", file2);

        assertContainsAll(memoryService.readArchivedTopic("20250514-153000-memory-service-architecture"),
                "Memory Service Architecture", "Alice", "messageCount: 2");
        assertEquals(2, memoryService.listArchivedTopicSummaries().size());

        Path plansDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        Files.writeString(plansDir.resolve("20250514-120000-feature-plan.md"), """
                ---
                id: "20250514-120000-feature-plan"
                title: "Archive Browser Feature"
                completedBy: "Charlie"
                completedOn: "2025-05-14T12:00:00Z"
                ---

                # Implementation Plan: Archive Browser Feature
                UniqueBodyMatchToken in plan steps
                """, StandardCharsets.UTF_8);

        assertEquals(1, memoryService.listArchivedPlanSummaries().size());
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), eq(plansDir.toAbsolutePath().normalize()), eq(false)))
                    .thenReturn(new ProcessResult(0,
                            "./20250514-120000-feature-plan.md:UniqueBodyMatchToken in plan steps", "", false));
            List<MemoryService.ArchiveEntrySummary> planMatched = memoryService
                    .searchArchivedPlans("UniqueBodyMatchToken");
            assertEquals(1, planMatched.size());
            assertEquals("UniqueBodyMatchToken in plan steps", planMatched.get(0).matchSnippet());
        }
    }

    @Test
    void testUnifiedRipgrepSearchCappingAndTitleDerivation() {
        memoryService.put("global", "foo", "Global Foo", List.of("global"), "UniqueCrossScopeToken alpha");
        memoryService.put("project", "bar", "Project Bar", List.of("project"), "UniqueCrossScopeToken beta");
        String simulatedRgStdout = roxyHomeDir.resolve("memory/memories.json").toAbsolutePath().normalize()
                + ":12:UniqueCrossScopeToken alpha\n"
                + projectDataDir.resolve("memory/memories.json").toAbsolutePath().normalize()
                + ":5:UniqueCrossScopeToken beta\n";

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, simulatedRgStdout, "", false));
            assertContainsAll(memoryService.search("UniqueCrossScopeToken").content(), "memories.json:12:",
                    "memories.json:5:");
            assertContainsAll(memoryService.searchIgnoreCase("uniquecrossscopetoken").content(), "memories.json:12:");
        }
        assertTrue(FrontmatterUtils.stripFrontmatter("---\nid: '1'\n---\n\n# Body").startsWith("# Body"));
        List<ChatTranscriptEntry> entries = List
                .of(new ChatTranscriptEntry("user", "  # How does repo map packing work?  ", "10:00:00"));
        assertEquals("How does repo map packing work", MemoryService.deriveDefaultTopicTitle(entries));
        assertEquals("Custom User Title", MemoryService.resolveTopicTitle("  **Custom User Title**  ", entries));
        assertEquals("How does repo map packing work", MemoryService.resolveTopicTitle("   ", entries));
    }

    @Test
    void testRenameAndDeleteArchivedTopic() throws Exception {
        List<org.roxycode.app.events.ArchiveUpdatedEvent> archiveEvents = new ArrayList<>();
        MemoryService topicMemoryService = new MemoryService(settingsService, projectService, event -> {
            if (event instanceof org.roxycode.app.events.ArchiveUpdatedEvent archiveEvent) {
                archiveEvents.add(archiveEvent);
            }
        });
        List<ChatTranscriptEntry> entries = List.of(new ChatTranscriptEntry("user", "Initial question", "10:00:00"),
                new ChatTranscriptEntry("ai", "Initial answer", "10:00:05"));
        String filename = topicMemoryService.archiveTopicConversation("Original Topic", "Alice", "2025-05-14T15:30:00Z",
                entries);
        assertNotNull(filename);

        EditorResult blankErr = topicMemoryService.renameArchivedTopic(filename, "   ");
        assertFalse(blankErr.success());

        EditorResult renameRes = topicMemoryService.renameArchivedTopic(filename, "  **Updated Topic Name**  ");
        assertTrue(renameRes.success());
        assertEquals("Updated Topic Name", renameRes.content());
        assertEquals(1, archiveEvents.size());
        assertEquals(new org.roxycode.app.events.ArchiveUpdatedEvent("topic", filename), archiveEvents.get(0));
        String updatedMd = topicMemoryService.readArchivedTopic(filename);
        assertContainsAll(updatedMd, "title: \"Updated Topic Name\"", "# Topic: Updated Topic Name", "Initial answer");

        EditorResult deleteRes = topicMemoryService.deleteArchivedTopic(filename);
        assertTrue(deleteRes.success());
        assertEquals(filename, deleteRes.content());
        assertEquals("", topicMemoryService.readArchivedTopic(filename));
        assertEquals(2, archiveEvents.size());

        EditorResult missingErr = topicMemoryService.deleteArchivedTopic(filename);
        assertFalse(missingErr.success());
    }

    @Test
    void testRenameAndDeleteArchivedPlan() throws Exception {
        List<org.roxycode.app.events.ArchiveUpdatedEvent> archiveEvents = new ArrayList<>();
        MemoryService planMemoryService = new MemoryService(settingsService, projectService, event -> {
            if (event instanceof org.roxycode.app.events.ArchiveUpdatedEvent archiveEvent) {
                archiveEvents.add(archiveEvent);
            }
        });
        Path plansDir = Files.createDirectories(projectDataDir.resolve("archive/plans"));
        String filename = "20261113-120000-sample-plan.md";
        String initialMd = "---\nid: '20261113-120000-sample-plan'\ntype: plan\ntitle: 'Original Plan'\n---\n\n"
                + "# Implementation Plan: Original Plan\n\n## Goal\nShip it.\n";
        Files.writeString(plansDir.resolve(filename), initialMd, StandardCharsets.UTF_8);

        EditorResult blankErr = planMemoryService.renameArchivedPlan(filename, "   ");
        assertFalse(blankErr.success());

        EditorResult renameRes = planMemoryService.renameArchivedPlan(filename, "  **Updated Plan Name**  ");
        assertTrue(renameRes.success());
        assertEquals("Updated Plan Name", renameRes.content());
        assertEquals(1, archiveEvents.size());
        assertEquals(new org.roxycode.app.events.ArchiveUpdatedEvent(
                org.roxycode.app.events.ArchiveUpdatedEvent.TYPE_PLAN, filename), archiveEvents.get(0));
        String updatedMd = planMemoryService.readArchivedPlan(filename);
        assertContainsAll(updatedMd, "title: \"Updated Plan Name\"", "# Implementation Plan: Updated Plan Name",
                "Ship it.");
        EditorResult deleteRes = planMemoryService.deleteArchivedPlan(filename);
        assertTrue(deleteRes.success());
        assertEquals(filename, deleteRes.content());
        assertEquals("", planMemoryService.readArchivedPlan(filename));
        assertEquals(2, archiveEvents.size());
        assertEquals(new org.roxycode.app.events.ArchiveUpdatedEvent(
                org.roxycode.app.events.ArchiveUpdatedEvent.TYPE_PLAN, filename), archiveEvents.get(1));

        EditorResult missingErr = planMemoryService.deleteArchivedPlan(filename);
        assertFalse(missingErr.success());
    }
}
