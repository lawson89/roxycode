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
import org.roxycode.app.ai.services.MemoryService.NoteDetail;
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
    void testMemoryIndexNotesCrudStructuredDetailsAndEvents() {
        assertTrue(memoryService.updateGlobalMemory("# Global Prefs").success());
        assertEquals("# Global Prefs", memoryService.readGlobalMemory());
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0).globalScope());

        assertTrue(memoryService.updateProjectMemory("# Project Rules").success());
        assertEquals("# Project Rules", memoryService.readProjectMemory());
        assertEquals(2, publishedEvents.size());
        assertFalse(publishedEvents.get(1).globalScope());

        assertTrue(memoryService
                .saveGlobalNote("Java Style.md", "Java Style Guide", "java, style", "Use immutable records.")
                .success());
        assertContainsAll(memoryService.readGlobalNote("java-style"), "id: ", "java-style", "Java Style Guide");
        assertEquals(1, memoryService.listGlobalNotes().size());

        List<NoteDetail> globalDetails = memoryService.listGlobalNoteDetails();
        assertEquals(1, globalDetails.size());
        NoteDetail globalDetail = globalDetails.get(0);
        assertEquals("java-style", globalDetail.slug());
        assertEquals("Java Style Guide", globalDetail.title());
        assertEquals("java, style", globalDetail.tags());
        assertEquals("Use immutable records.", globalDetail.body());
        assertFalse(globalDetail.updated().isEmpty());

        assertTrue(memoryService.saveProjectNote("swing-tables", "Swing Tables", "ui, swing", "Use HTML tables.")
                .success());
        assertContainsAll(memoryService.readProjectNote("swing-tables.md"), "swing-tables", "Swing Tables");
        assertEquals(1, memoryService.listProjectNotes().size());

        List<NoteDetail> projectDetails = memoryService.listProjectNoteDetails();
        assertEquals(1, projectDetails.size());
        NoteDetail projectDetail = projectDetails.get(0);
        assertEquals("Swing Tables", projectDetail.title());
        assertEquals("ui, swing", projectDetail.tags());
        assertEquals("Use HTML tables.", projectDetail.body());

        assertTrue(memoryService.deleteGlobalNote("java-style").success());
        assertEquals("", memoryService.readGlobalNote("java-style"));
        assertTrue(memoryService.deleteProjectNote("swing-tables").success());
        assertEquals(6, publishedEvents.size());

        for (String invalid : new String[]{null, "", "   ", "../secret", "notes/sub", "---"}) {
            assertThrows(IllegalArgumentException.class, () -> MemoryService.sanitizeSlug(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> memoryService.readArchivedPlan("../secret.md"));

        when(projectService.hasActiveProject()).thenReturn(false);
        assertFalse(memoryService.updateProjectMemory("content").success());
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
        memoryService.saveGlobalNote("foo", "Global Foo", "global", "UniqueCrossScopeToken alpha");
        memoryService.saveProjectNote("bar", "Project Bar", "project", "UniqueCrossScopeToken beta");
        String simulatedRgStdout = roxyHomeDir.resolve("memory/notes/foo.md").toAbsolutePath().normalize()
                + ":12:UniqueCrossScopeToken alpha\n"
                + projectDataDir.resolve("memory/notes/bar.md").toAbsolutePath().normalize()
                + ":5:UniqueCrossScopeToken beta\n";

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, simulatedRgStdout, "", false));
            assertContainsAll(memoryService.search("UniqueCrossScopeToken").content(), "foo.md:12:", "bar.md:5:");
            assertContainsAll(memoryService.searchIgnoreCase("uniquecrossscopetoken").content(), "foo.md:12:");
        }

        assertTrue(MemoryService.capPinnedMemoryForPrompt("line\n".repeat(250))
                .contains("truncated at 200 lines / 8 KB limit"));
        assertTrue(FrontmatterUtils.stripFrontmatter("---\nid: '1'\n---\n\n# Body").startsWith("# Body"));
        List<ChatTranscriptEntry> entries = List
                .of(new ChatTranscriptEntry("user", "  # How does repo map packing work?  ", "10:00:00"));
        assertEquals("How does repo map packing work", MemoryService.deriveDefaultTopicTitle(entries));
        assertEquals("Custom User Title", MemoryService.resolveTopicTitle("  **Custom User Title**  ", entries));
        assertEquals("How does repo map packing work", MemoryService.resolveTopicTitle("   ", entries));
    }
}
