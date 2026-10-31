package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.PromptService.ContextFileItem;
import org.roxycode.app.ui.SwingTestUtils;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private SettingsService settingsService;
    @Mock
    private PlanService planService;
    @Mock
    private JexlServiceRegistry jexlServiceRegistry;
    @Mock
    private GitService gitService;
    @Mock
    private ProjectAnalysisService projectAnalysisService;
    @Mock
    private RepoMapPackerService repoMapPackerService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PromptService promptService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        promptService = new PromptService(projectService, settingsService, planService, jexlServiceRegistry, gitService,
                projectAnalysisService, repoMapPackerService, null, eventPublisher);
    }

    @Test
    void testGlobalAndProjectContextFoldersAndTopLevelTextDiscovery() throws IOException {
        Path projectDataDir = tempDir.resolve("project-data");
        Path projectRoot = Files.createDirectories(tempDir.resolve("project-root"));
        when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
        when(settingsService.getSettings()).thenReturn(new AppSettings());

        assertEquals("", promptService.loadGlobalContext());
        assertEquals("", promptService.loadProjectContext());
        assertTrue(promptService.listGlobalContextFiles().isEmpty());
        assertTrue(promptService.listProjectContextFiles().isEmpty());

        Path globalDir = Files.createDirectories(tempDir.resolve("global-context"));
        Files.writeString(globalDir.resolve("b_global.txt"), "Global B text");
        Files.writeString(globalDir.resolve("a_global.md"), "Global A markdown");
        Files.writeString(globalDir.resolve(".hidden.md"), "Hidden file");
        Files.write(globalDir.resolve("binary.bin"), new byte[]{0x01, 0x00, 0x02});
        Files.writeString(Files.createDirectories(globalDir.resolve("nested")).resolve("sub.md"),
                "Nested should be ignored");

        promptService.setGlobalContextFolders(List.of("  " + globalDir + "  ", globalDir.toString()));
        verify(settingsService).saveSettings();
        verify(eventPublisher).publishEvent(new ContextChangedEvent(true));
        assertEquals(List.of(globalDir.toString()), promptService.getGlobalContextFolders());

        List<ContextFileItem> globalFiles = promptService.listGlobalContextFiles();
        assertEquals(2, globalFiles.size());
        assertEquals("a_global.md", globalFiles.get(0).fileName());
        assertEquals("b_global.txt", globalFiles.get(1).fileName());

        Path projDocsDir = Files.createDirectories(projectRoot.resolve("docs"));
        Files.writeString(projDocsDir.resolve("z_spec.json"), "{spec: true}");
        Files.writeString(projDocsDir.resolve("a_architecture.md"), "Architecture guidelines");
        Files.write(projDocsDir.resolve("image.png"), new byte[]{(byte) 0x89, 0x50, 0x00, 0x47});
        Files.writeString(Files.createDirectories(projDocsDir.resolve("subdir")).resolve("ignored.md"),
                "Subdir ignored");

        promptService.setProjectContextFolders(List.of("docs", "docs"));
        verify(eventPublisher).publishEvent(new ContextChangedEvent(false));
        assertEquals(List.of("docs"), promptService.getProjectContextFolders());

        List<ContextFileItem> projectFiles = promptService.listProjectContextFiles();
        assertEquals(2, projectFiles.size());
        assertEquals("a_architecture.md", projectFiles.get(0).fileName());
        assertEquals("z_spec.json", projectFiles.get(1).fileName());

        String globalContext = promptService.loadGlobalContext();
        SwingTestUtils.assertContainsAll(globalContext, "## Global Context: a_global.md\nGlobal A markdown",
                "## Global Context: b_global.txt\nGlobal B text");
        SwingTestUtils.assertContainsNone(globalContext, "Hidden file", "Nested should be ignored");

        clearInvocations(projectService);
        String projectContext = promptService.loadProjectContext();
        verify(projectService, times(1)).resolveProjectDataPath(PromptService.PROJECT_CONTEXT_CONFIG_FILE);
        SwingTestUtils.assertContainsAll(projectContext,
                "## Project Context: a_architecture.md\nArchitecture guidelines",
                "## Project Context: z_spec.json\n{spec: true}");
        SwingTestUtils.assertContainsNone(projectContext, "Subdir ignored");
        assertTrue(projectContext.indexOf("a_architecture.md") < projectContext.indexOf("z_spec.json"));
    }

    @Test
    void testGetStableSessionAndSystemPrompt() throws IOException {
        Path projectDataDir = tempDir.resolve("project-data");
        Path globalDir = Files.createDirectories(tempDir.resolve("global-rules"));
        Path projectRulesDir = Files.createDirectories(tempDir.resolve("project-rules"));
        Files.writeString(globalDir.resolve("global.md"), "GLOBAL RULES");
        Files.writeString(projectRulesDir.resolve("rules.md"), "CUSTOM RULES");

        AppSettings settings = new AppSettings();
        settings.setGlobalContextFolders(List.of(globalDir.toString()));
        when(settingsService.getSettings()).thenReturn(settings);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
        promptService.setProjectContextFolders(List.of(projectRulesDir.toString()));

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(jexlServiceRegistry.getDocumentation(WorkflowPhase.CODE)).thenReturn("JEXL DOCS");
        when(gitService.getStatus()).thenReturn("M file.java");
        when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");
        when(repoMapPackerService.generateRepoMap()).thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));

        String stable = promptService.getStableContext();
        SwingTestUtils.assertContainsAll(stable, "## JEXL CONTEXT", "## Global Context: global.md\nGLOBAL RULES",
                "## Project Context: rules.md\nCUSTOM RULES");

        String session = promptService.getSessionContext();
        SwingTestUtils.assertContainsAll(session, "DOMINANT LANGUAGE: Java", "CURRENT PHASE: CODE", "Senior Developer",
                "JEXL DOCS", "M file.java");
        SwingTestUtils.assertContainsNone(session, "## CURRENT PLAN PROGRESS");

        SwingTestUtils.assertContainsAll(promptService.buildSystemPrompt(WorkflowPhase.CODE, "EXTRA INSTRUCTIONS"),
                stable, "REPO MAP CONTENT", session, "EXTRA INSTRUCTIONS");
    }

    @Test
    void testGetSessionContextIncludesCurrentPlanProgressWithZeroBasedIndices() {
        when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");
        when(jexlServiceRegistry.getDocumentation(WorkflowPhase.CODE)).thenReturn("JEXL DOCS");
        when(gitService.getStatus()).thenReturn("");

        ImplementationPlan plan = new ImplementationPlan("Test Plan", "Goal", List.of("Req 1"),
                List.of(new ImplementationPlan.TechStep("Update prompts", true),
                        new ImplementationPlan.TechStep("Update PlanService", false),
                        new ImplementationPlan.TechStep("Update PromptService", false)),
                true, null, null, null, null, null);
        when(planService.getCurrentPlan()).thenReturn(plan);

        SwingTestUtils.assertContainsAll(promptService.getSessionContext(WorkflowPhase.CODE),
                "## CURRENT PLAN PROGRESS", "- [x] 0. ~~Update prompts~~", "- [ ] 1. Update PlanService",
                "- [ ] 2. Update PromptService");
    }

    @Test
    void testAgentMemoryContextInjectionAndEmptyState() {
        MemoryService memoryService = mock(MemoryService.class);
        PromptService psWithMemory = new PromptService(projectService, settingsService, planService,
                jexlServiceRegistry, gitService, projectAnalysisService, repoMapPackerService, memoryService,
                eventPublisher);

        when(memoryService.readGlobalMemory()).thenReturn("");
        when(memoryService.listGlobalNotes()).thenReturn(List.of());
        when(memoryService.readProjectMemory()).thenReturn("");
        when(memoryService.listProjectNotes()).thenReturn(List.of());
        when(memoryService.listArchivedPlans()).thenReturn(List.of());

        assertEquals("", psWithMemory.loadAgentMemoryContext());
        SwingTestUtils.assertContainsNone(psWithMemory.getStableContext(), "## GLOBAL AGENT MEMORY",
                "## PROJECT AGENT MEMORY & ARCHIVE");

        when(memoryService.readGlobalMemory()).thenReturn("# Global Prefs\n- Use Java 21");
        when(memoryService.listGlobalNotes())
                .thenReturn(List.of("style.md - 'Style Guide' [java] (2025-05-14T00:00:00Z)"));
        when(memoryService.readProjectMemory()).thenReturn("# Project Rules\n- Use MigLayout");
        when(memoryService.listProjectNotes())
                .thenReturn(List.of("swing-tables.md - 'Swing Tables' [ui, swing] (2025-05-14T00:00:00Z)"));
        when(memoryService.listArchivedPlans()).thenReturn(List.of("p6.md: Plan 6", "p5.md: Plan 5", "p4.md: Plan 4",
                "p3.md: Plan 3", "p2.md: Plan 2", "p1.md: Plan 1"));
        when(memoryService.listArchivedTopics()).thenReturn(List.of("t6.md: Topic 6", "t5.md: Topic 5",
                "t4.md: Topic 4", "t3.md: Topic 3", "t2.md: Topic 2", "t1.md: Topic 1"));

        String stable = psWithMemory.getStableContext();
        SwingTestUtils.assertContainsAll(stable, "## GLOBAL AGENT MEMORY", "# Global Prefs\n- Use Java 21", "style.md",
                "Use memoryService.readGlobalNote(slug) or memoryService.search(pattern) to inspect.",
                "## PROJECT AGENT MEMORY & ARCHIVE", "# Project Rules\n- Use MigLayout", "swing-tables.md",
                "### Recent Archived Plans", "- p6.md: Plan 6", "- p2.md: Plan 2", "### Recent Archived Topics",
                "- t6.md: Topic 6", "- t2.md: Topic 2",
                "Use memoryService.readProjectNote(slug), memoryService.readArchivedPlan(filename), memoryService.readArchivedTopic(filename), or memoryService.search(pattern) to inspect.");
        SwingTestUtils.assertContainsNone(stable, "- p1.md: Plan 1", "- t1.md: Topic 1");
    }
}
