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
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.PromptService.ContextFileItem;
import org.roxycode.app.ui.SwingTestUtils;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Test
    void testConfiguredDatabasesContextIncludedOnlyWhenGranted() {
        DatabaseConfigService dbConfigService = mock(DatabaseConfigService.class);
        when(dbConfigService.listActiveProjectConnections()).thenReturn(List.of());
        PromptService svc = new PromptService(projectService, settingsService, planService, jexlServiceRegistry,
                gitService, projectAnalysisService, repoMapPackerService, null, dbConfigService);
        assertEquals("", svc.loadConfiguredDatabasesContext());

        org.roxycode.app.model.DatabaseConnectionConfig cfg = new org.roxycode.app.model.DatabaseConnectionConfig(
                "qa-pg", "QA Postgres", "Core order fulfillment", "postgresql", "jdbc:postgresql://secret-host:5432/qa",
                "ro_user", "keyring", "", List.of(), "", "public", 15, 200);
        org.roxycode.app.model.ProjectDatabaseGrant grant = new org.roxycode.app.model.ProjectDatabaseGrant("qa-pg",
                "billing", "Billing QA tables");
        when(dbConfigService.listActiveProjectConnections()).thenReturn(List
                .of(new DatabaseConfigService.ResolvedDatabaseConnection(cfg, grant, "billing", "Billing QA tables")));

        String section = svc.loadConfiguredDatabasesContext();
        assertTrue(section.contains("## CONFIGURED DATABASES"));
        assertTrue(section.contains("qa-pg"));
        assertTrue(section.contains("billing"));
        assertTrue(section.contains("Core order fulfillment"));
        assertTrue(section.contains("Project notes: Billing QA tables"));
        assertFalse(section.contains("secret-host"));
        assertFalse(section.contains("ro_user"));
    }

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
        lenient().when(settingsService.getSettings()).thenReturn(new AppSettings());
        lenient().when(settingsService.getRoxyHomeDir()).thenReturn(tempDir.resolve("roxy-home"));
        projectService = spy(new ProjectService(settingsService, eventPublisher));
        lenient().when(jexlServiceRegistry.isGitAvailable()).thenReturn(true);
        promptService = new PromptService(projectService, settingsService, planService, jexlServiceRegistry, gitService,
                projectAnalysisService, repoMapPackerService, null);
    }

    @Test
    void testGlobalAndProjectContextFoldersAndTopLevelTextDiscovery() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project-root"));
        projectService.setCurrentProjectRoot(projectRoot);

        assertEquals("", promptService.loadGlobalContext());
        assertEquals("", promptService.loadProjectContext());

        Path globalDir = Files.createDirectories(projectService.getGlobalContextDir());
        Files.writeString(globalDir.resolve("b_global.txt"), "Global B text");
        Files.writeString(globalDir.resolve("a_global.md"), "Global A markdown");
        Files.writeString(globalDir.resolve(".hidden.md"), "Hidden file");
        Files.write(globalDir.resolve("binary.bin"), new byte[]{0x01, 0x00, 0x02});
        Files.writeString(Files.createDirectories(globalDir.resolve("nested")).resolve("sub.md"),
                "Nested should be ignored");

        List<ContextFileItem> globalFiles = promptService.listGlobalContextFiles();
        assertEquals(List.of("a_global.md", "b_global.txt"),
                globalFiles.stream().map(ContextFileItem::fileName).toList());

        Path projContextDir = Files.createDirectories(projectService.getProjectContextDir());
        Files.writeString(projContextDir.resolve("z_spec.json"), "{spec: true}");
        Files.writeString(projContextDir.resolve("a_architecture.md"), "Architecture guidelines");
        Files.writeString(projContextDir.resolve("b_billing.md"),
                "---\ntitle: Billing Spec\ntags: billing, stripe\n---\n# Secret Body\nDo not inject full body.");
        Files.write(projContextDir.resolve("image.png"), new byte[]{(byte) 0x89, 0x50, 0x00, 0x47});

        String globalContext = promptService.loadGlobalContext();
        SwingTestUtils.assertContainsAll(globalContext, "## Global Context",
                "Full-Injected Files: `@global-context/a_global.md`, `@global-context/b_global.txt`",
                "## Global Context: a_global.md\nGlobal A markdown", "## Global Context: b_global.txt\nGlobal B text");
        SwingTestUtils.assertContainsNone(globalContext, "Hidden file", "Nested should be ignored");

        String projectContext = promptService.loadProjectContext();
        SwingTestUtils.assertContainsAll(projectContext, "## Project Context",
                "Full-Injected Files: `context/a_architecture.md`, `context/z_spec.json`", "Frontmatter-Indexed Files",
                "`context/b_billing.md`:", "title=\"Billing Spec\"", "tags=\"billing, stripe\"",
                "## Project Context: a_architecture.md\nArchitecture guidelines",
                "## Project Context: z_spec.json\n{spec: true}");
        SwingTestUtils.assertContainsNone(projectContext, "Do not inject full body.");
        assertTrue(projectContext.indexOf("a_architecture.md") < projectContext.indexOf("z_spec.json"));

        Path projRef = Files.createDirectories(projectService.getProjectRefsDir().resolve("proj-billing-service"));
        Path globalRef = Files.createDirectories(projectService.getGlobalRefsDir().resolve("global-auth-lib"));

        assertEquals(2, projectService.listGlobalReferenceFolderMounts().size());
        assertEquals("global-auth-lib", projectService.listGlobalReferenceFolderMounts().get(0).alias());
        assertEquals("global-context", projectService.listGlobalReferenceFolderMounts().get(1).alias());
        assertEquals(1, projectService.listProjectReferenceFolderMounts().size());
        assertEquals("proj-billing-service", projectService.listProjectReferenceFolderMounts().get(0).alias());

        String refCatalog = promptService.loadReferenceFoldersContext();
        SwingTestUtils.assertContainsAll(refCatalog, "## READ-ONLY REFERENCE FOLDERS", "@proj-billing-service",
                projRef.toAbsolutePath().normalize().toString(), "@global-auth-lib",
                globalRef.toAbsolutePath().normalize().toString(), "@global-context");
        assertTrue(promptService.getStableContext().contains(refCatalog));
    }

    @Test
    void testGetStableSessionAndSystemPrompt() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project-root-2"));
        projectService.setCurrentProjectRoot(projectRoot);
        Path globalDir = Files.createDirectories(projectService.getGlobalContextDir());
        Path projectRulesDir = Files.createDirectories(projectService.getProjectContextDir());
        Files.writeString(globalDir.resolve("global.md"), "GLOBAL RULES");
        Files.writeString(projectRulesDir.resolve("rules.md"), "CUSTOM RULES");

        AppSettings settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(jexlServiceRegistry.getDocumentation(WorkflowPhase.CODE)).thenReturn("JEXL DOCS");
        when(gitService.getCurrentBranch()).thenReturn("feature/branch-sync");
        when(gitService.getStatus()).thenReturn("M file.java");
        when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");
        when(repoMapPackerService.generateRepoMap()).thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));

        String stable = promptService.getStableContext();
        SwingTestUtils.assertContainsAll(stable, "## JEXL CONTEXT", "## ROXYCODE USER GUIDE",
                "# RoxyCode Built-In User Guide & Reference", "[commands]", "[sandbox]",
                "## Global Context: global.md\nGLOBAL RULES", "## Project Context: rules.md\nCUSTOM RULES");

        String session = promptService.getSessionContext();
        SwingTestUtils.assertContainsAll(session, "DOMINANT LANGUAGE: Java", "CURRENT PHASE: CODE", "JEXL DOCS",
                "M file.java");
        SwingTestUtils.assertContainsNone(session, "## CURRENT PLAN PROGRESS");

        settings.setMaxAgentToolTurns(77);
        assertTrue(promptService.loadPhasePrompt(WorkflowPhase.EXPLORE).contains("77"));
        assertFalse(promptService.loadPhasePrompt(WorkflowPhase.PLAN).contains("${maxTurns}"));
        assertFalse(promptService.loadPhasePrompt(WorkflowPhase.CODE).contains("${maxTurns}"));
        assertFalse(promptService.loadPhasePrompt(WorkflowPhase.REVIEW).contains("${maxTurns}"));
        String fullSystemPrompt = promptService.buildSystemPrompt(WorkflowPhase.CODE);
        String codePhasePrompt = promptService.loadPhasePrompt(WorkflowPhase.CODE);
        SwingTestUtils.assertContainsAll(fullSystemPrompt, stable, "REPO MAP CONTENT", codePhasePrompt, session);
        int stableIdx = fullSystemPrompt.indexOf(stable);
        int repoMapIdx = fullSystemPrompt.indexOf("## REPOSITORY MAP");
        int phasePromptIdx = fullSystemPrompt.indexOf(codePhasePrompt);
        int sessionIdx = fullSystemPrompt.indexOf("## SESSION CONTEXT");
        assertTrue(stableIdx >= 0 && stableIdx < repoMapIdx);
        assertTrue(repoMapIdx < phasePromptIdx);
        assertTrue(phasePromptIdx < sessionIdx);

        ImplementationPlan plan = new ImplementationPlan("Test Plan", "Goal", List.of("Req 1"),
                List.of(new ImplementationPlan.TechStep("Update prompts", true),
                        new ImplementationPlan.TechStep("Update PlanService", false)),
                true, null, null, null, null, List.of(new ImplementationPlan.LogEntry("2026-04-06T12:05:30Z",
                        "Reviewer", "REVIEW -> CODE: Fix edge case")));
        when(planService.getCurrentPlan()).thenReturn(plan);
        String staticPhaseContext = promptService.getStaticPhaseContext(WorkflowPhase.CODE);
        SwingTestUtils.assertContainsAll(staticPhaseContext, "DOMINANT LANGUAGE: Java", "CURRENT PHASE: CODE",
                "JEXL DOCS");
        SwingTestUtils.assertContainsNone(staticPhaseContext, "## GIT STATUS", "## CURRENT PLAN PROGRESS");

        String volatileContext = promptService.getVolatileSessionContext();
        SwingTestUtils.assertContainsAll(volatileContext, "## GIT STATUS", "Branch: feature/branch-sync", "M file.java",
                "## CURRENT PLAN PROGRESS", "**Title:** Test Plan");

        when(gitService.getStatus()).thenReturn("");
        SwingTestUtils.assertContainsAll(promptService.getVolatileSessionContext(), "## GIT STATUS",
                "Branch: feature/branch-sync");
        when(gitService.getStatus()).thenReturn("M file.java");

        String promptTurn1 = promptService.buildSystemPrompt(WorkflowPhase.CODE);
        when(gitService.getStatus()).thenReturn("M file.java\nM other.java");
        ImplementationPlan updatedPlan = new ImplementationPlan("Test Plan", "Goal", List.of("Req 1"),
                List.of(new ImplementationPlan.TechStep("Update prompts", true),
                        new ImplementationPlan.TechStep("Update PlanService", true)),
                true, null, null, null, null, List.of(new ImplementationPlan.LogEntry("2026-04-06T12:05:30Z",
                        "Reviewer", "REVIEW -> CODE: Fix edge case")));
        when(planService.getCurrentPlan()).thenReturn(updatedPlan);
        String promptTurn2 = promptService.buildSystemPrompt(WorkflowPhase.CODE);

        int gitStatusIdx1 = promptTurn1.indexOf("## GIT STATUS");
        int gitStatusIdx2 = promptTurn2.indexOf("## GIT STATUS");
        assertTrue(gitStatusIdx1 > 0);
        assertEquals(gitStatusIdx1, gitStatusIdx2);
        assertEquals(promptTurn1.substring(0, gitStatusIdx1), promptTurn2.substring(0, gitStatusIdx2));

        when(planService.getCurrentPlan()).thenReturn(plan);
        SwingTestUtils.assertContainsAll(promptService.getSessionContext(WorkflowPhase.CODE),
                "## CURRENT PLAN PROGRESS", "**Title:** Test Plan", "**Goal:** Goal", "### Requirements", "- Req 1",
                "### Technical Steps", "- [x] 0. ~~Update prompts~~", "- [ ] 1. Update PlanService", "### Handoff Log",
                "- [2026-04-06 12:05:30] Reviewer: REVIEW -> CODE: Fix edge case");

        clearInvocations(gitService);
        when(jexlServiceRegistry.isGitAvailable()).thenReturn(false);
        String sessionWithoutGit = promptService.getSessionContext(WorkflowPhase.CODE);
        verify(gitService, never()).getStatus();
        SwingTestUtils.assertContainsNone(sessionWithoutGit, "## GIT STATUS");
    }

    @Test
    void testAgentMemoryContextInjectionAndEmptyState() {
        MemoryService memoryService = mock(MemoryService.class);
        PromptService psWithMemory = new PromptService(projectService, settingsService, planService,
                jexlServiceRegistry, gitService, projectAnalysisService, repoMapPackerService, memoryService);

        when(memoryService.list(MemoryService.SCOPE_GLOBAL)).thenReturn(List.of());
        when(memoryService.list(MemoryService.SCOPE_PROJECT)).thenReturn(List.of());
        when(memoryService.listArchivedPlans()).thenReturn(List.of());
        when(memoryService.listArchivedTopics()).thenReturn(List.of());

        assertEquals("", psWithMemory.loadAgentMemoryContext());

        when(memoryService.list(MemoryService.SCOPE_GLOBAL)).thenReturn(List.of(
                new MemoryService.MemoryMetadata("global", "no-ternary", "Use explicit if/else", List.of("java"), false,
                        "2026-01-01T00:00:00Z"),
                new MemoryService.MemoryMetadata("global", "style-guide", "Follow style guide",
                        List.of("java", "style"), true, "2026-01-01T00:00:00Z")));
        when(memoryService.list(MemoryService.SCOPE_PROJECT))
                .thenReturn(List.of(new MemoryService.MemoryMetadata("project", "swing-tables", "Use HTML 3.2 tables",
                        List.of("ui", "swing"), true, "2026-01-01T00:00:00Z")));
        when(memoryService.listArchivedPlans()).thenReturn(
                List.of("p6.md: Plan 6", "p5.md: Plan 5", "p4.md: Plan 4", "p3.md: Plan 3", "p2.md: Plan 2", "p1.md"));
        when(memoryService.listArchivedTopics()).thenReturn(List.of("t6.md: Topic 6", "t5.md: Topic 5",
                "t4.md: Topic 4", "t3.md: Topic 3", "t2.md: Topic 2", "t1.md"));

        String stable = psWithMemory.getStableContext();
        SwingTestUtils.assertContainsAll(stable, "## GLOBAL AGENT MEMORY",
                "- `no-ternary` [java]: Use explicit if/else",
                "- `style-guide` [java, style] (+details): Follow style guide", "memoryService.read('global', id)",
                "## PROJECT AGENT MEMORY & ARCHIVE", "- `swing-tables` [ui, swing] (+details): Use HTML 3.2 tables",
                "memoryService.read('project', id)", "- p6.md: Plan 6", "- p2.md: Plan 2", "- t6.md: Topic 6",
                "- t2.md: Topic 2");
        SwingTestUtils.assertContainsNone(stable, "- p1.md", "- t1.md");
    }
}
