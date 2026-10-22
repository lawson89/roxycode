package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock private ProjectService projectService;
    @Mock private SettingsService settingsService;
    @Mock private PlanService planService;
    @Mock private JexlServiceRegistry jexlServiceRegistry;
    @Mock private GitService gitService;
    @Mock private ProjectAnalysisService projectAnalysisService;
    @Mock private RepoMapPackerService repoMapPackerService;

    private PromptService promptService;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        promptService =
                new PromptService(
                        projectService,
                        settingsService,
                        planService,
                        jexlServiceRegistry,
                        gitService,
                        projectAnalysisService,
                        repoMapPackerService);
    }

    @Test
    void testLoadCoreWorkflowPrompt() {
        String prompt = promptService.getStableContext();
        assertNotNull(prompt);
        assertTrue(prompt.contains("RoxyCode Workflow Protocol"));
    }

    /**
     * Verifies that the core workflow prompt documents RoxyCode's 4 phases and actual services,
     * while excluding references to external orchestrator APIs.
     */
    @Test
    void testCoreWorkflowPromptReflectsRoxyCodePhasesAndServices() {
        String prompt = promptService.getStableContext();
        assertNotNull(prompt);
        assertTrue(prompt.contains("EXPLORE (Technical Mentor)"));
        assertTrue(prompt.contains("PLAN (Lead Architect)"));
        assertTrue(prompt.contains("CODE (Senior Developer)"));
        assertTrue(prompt.contains("REVIEW (Code Reviewer)"));
        assertTrue(prompt.contains("requestDevelopment"));
        assertTrue(prompt.contains("requestReview"));
        assertTrue(prompt.contains("requestFixes"));
        assertTrue(prompt.contains("requestCompletion"));
        assertTrue(prompt.contains("buildToolService"));
        assertTrue(prompt.contains("fileEditorService"));
        assertTrue(prompt.contains("fileReadService"));
        assertTrue(prompt.contains("grepService"));
        assertTrue(prompt.contains("gitService"));
        assertTrue(prompt.contains("planService"));
        assertFalse(prompt.contains("textEditorService"));
        assertFalse(prompt.contains("createPlan("));
        assertFalse(prompt.contains("completeStep("));
        assertFalse(prompt.contains("validateReadyForApproval"));
    }

    @Test
    void testLoadAllPrompts() {
        String allPrompts = promptService.loadAllPrompts();
        assertNotNull(allPrompts);
        // core_workflow.md should NOT be in the result of loadAllPrompts
        assertFalse(allPrompts.contains("## core_workflow.md"));
    }

    @Test
    void testLoadAllDocs() {
        String allDocs = promptService.loadAllDocs();
        assertNotNull(allDocs);
        // jexl.md should NOT be in the result of loadAllDocs
        assertFalse(allDocs.contains("## jexl.md"));
    }

    @Test
    void testLoadReviewPrompt() {
        String prompt = promptService.loadPhasePrompt(WorkflowPhase.REVIEW);
        assertNotNull(prompt);
        assertTrue(prompt.contains("REVIEW"));
        assertTrue(prompt.contains("PHASE"));
        assertTrue(prompt.contains("buildToolService"));
        assertFalse(prompt.contains("genericBuildToolService"));
    }

    @Test
    void testLoadExplorePrompt() {
        String prompt = promptService.loadPhasePrompt(WorkflowPhase.EXPLORE);
        assertNotNull(prompt);
        assertTrue(prompt.contains("EXPLORE phase"));
        assertTrue(prompt.contains("grepService"));
        assertTrue(prompt.contains("fileReadService"));
        assertFalse(prompt.contains("textEditorService"));
        assertFalse(prompt.contains("readFiles"));
    }

    @Test
    void testLoadPlanPrompt() {
        String prompt = promptService.loadPhasePrompt(WorkflowPhase.PLAN);
        assertNotNull(prompt);
        assertTrue(prompt.contains("ANALYST"));
        assertTrue(prompt.contains("PLANNING"));
        assertTrue(prompt.contains("Requirements Gathering & Clarification"));
        assertTrue(prompt.contains("planService"));
        assertTrue(prompt.contains("buildToolService"));
        assertFalse(prompt.contains("createPlan"));
        assertFalse(prompt.contains("addRequirement"));
        assertFalse(prompt.contains("addConstraint"));
        assertFalse(prompt.contains("validateReadyForApproval"));
    }

    @Test
    void testLoadCodePrompt() {
        String prompt = promptService.loadPhasePrompt(WorkflowPhase.CODE);
        assertNotNull(prompt);
        assertTrue(prompt.contains("CODER"));
        assertTrue(prompt.contains("ACTING"));
    }

    @Test
    void testLoadPhasePrompt() {
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            String prompt = promptService.loadPhasePrompt(phase);
            assertNotNull(prompt);
            assertFalse(prompt.isBlank());
        }
    }

    @Test
    void testLoadProjectContextAndCrudOperations() throws IOException {
        Path projectDataDir = tempDir.resolve("project-data");
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));

        // Case 1: no context directory yet
        assertEquals("", promptService.loadProjectContext());
        assertTrue(promptService.listContextFiles().isEmpty());

        // Case 2: save context files and non-markdown file in projectDataDir/context
        promptService.saveContextFile("b_architecture.md", "Architecture guidelines");
        promptService.saveContextFile("a_rules.md", "Project rules");
        Path contextDir = projectDataDir.resolve("context");
        Files.writeString(contextDir.resolve("ignore.txt"), "Ignore me");

        assertEquals(List.of("a_rules.md", "b_architecture.md"), promptService.listContextFiles());
        assertEquals("Project rules", promptService.readContextFile("a_rules.md"));

        String context = promptService.loadProjectContext();
        assertTrue(context.contains("## Project Context: a_rules.md"));
        assertTrue(context.contains("Project rules"));
        assertTrue(context.contains("## Project Context: b_architecture.md"));
        assertTrue(context.contains("Architecture guidelines"));
        assertFalse(context.contains("ignore.txt"));

        // Alphabetical ordering check: a_rules before b_architecture
        int idxA = context.indexOf("a_rules.md");
        int idxB = context.indexOf("b_architecture.md");
        assertTrue(idxA < idxB, "Context files should be sorted alphabetically");

        // Delete a context file
        promptService.deleteContextFile("a_rules.md");
        assertEquals(List.of("b_architecture.md"), promptService.listContextFiles());
        assertEquals("", promptService.readContextFile("a_rules.md"));

        // Invalid filenames (path traversal or subdirectories) should be rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> promptService.saveContextFile("../evil.md", "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> promptService.saveContextFile("sub/rule.md", "bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> promptService.saveContextFile("sub" + (char) 92 + "rule.md", "bad"));
        assertThrows(
                IllegalArgumentException.class, () -> promptService.readContextFile("../evil.md"));
        assertThrows(
                IllegalArgumentException.class,
                () -> promptService.deleteContextFile("../evil.md"));

        // IOException on readContextFile, saveContextFile, and deleteContextFile throws
        // UncheckedIOException
        Path dirAsFile = contextDir.resolve("dir_conflict.md");
        Files.createDirectories(dirAsFile.resolve("nested"));
        assertThrows(
                UncheckedIOException.class, () -> promptService.readContextFile("dir_conflict.md"));
        assertThrows(
                UncheckedIOException.class,
                () -> promptService.saveContextFile("dir_conflict.md", "content"));
        assertThrows(
                UncheckedIOException.class,
                () -> promptService.deleteContextFile("dir_conflict.md"));
    }

    @Test
    void testGenerateExploreSystemPrompt() {
        AppSettings settings = new AppSettings();
        settings.setMaxAgentToolTurns(42);
        when(settingsService.getSettings()).thenReturn(settings);

        String prompt = promptService.generateExploreSystemPrompt();

        assertNotNull(prompt);
        assertTrue(prompt.contains("42"));
        assertFalse(prompt.contains("${maxTurns}"));
    }

    @Test
    void testGetStableSessionAndSystemPrompt() throws IOException {
        Path projectDataDir = tempDir.resolve("project-data");
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
        Path contextDir = projectDataDir.resolve("context");
        Files.createDirectories(contextDir);
        Files.writeString(contextDir.resolve("rules.md"), "CUSTOM RULES");

        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(jexlServiceRegistry.getDocumentation(WorkflowPhase.CODE)).thenReturn("JEXL DOCS");
        when(gitService.getStatus()).thenReturn("M file.java");
        when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");
        when(repoMapPackerService.generateRepoMap())
                .thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));

        String stable = promptService.getStableContext();
        assertTrue(stable.contains("RoxyCode Workflow Protocol"));
        assertTrue(stable.contains("## JEXL CONTEXT"));
        assertTrue(stable.contains("CUSTOM RULES"));

        String session = promptService.getSessionContext();
        assertTrue(session.contains("DOMINANT LANGUAGE: Java"));
        assertTrue(session.contains("CURRENT PHASE: CODE"));
        assertTrue(session.contains("Senior Developer"));
        assertTrue(session.contains("JEXL DOCS"));
        assertTrue(session.contains("M file.java"));

        String fullSystemPrompt =
                promptService.buildSystemPrompt(WorkflowPhase.CODE, "EXTRA INSTRUCTIONS");
        assertTrue(fullSystemPrompt.contains(stable));
        assertTrue(fullSystemPrompt.contains("REPO MAP CONTENT"));
        assertTrue(fullSystemPrompt.contains(session));
        assertTrue(fullSystemPrompt.contains("EXTRA INSTRUCTIONS"));
        assertFalse(session.contains("## CURRENT PLAN PROGRESS"));
    }

    @Test
    void testGetSessionContextIncludesCurrentPlanProgressWithZeroBasedIndices() {
        when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");
        when(jexlServiceRegistry.getDocumentation(WorkflowPhase.CODE)).thenReturn("JEXL DOCS");
        when(gitService.getStatus()).thenReturn("");

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Goal",
                        List.of("Req 1"),
                        List.of(
                                new ImplementationPlan.TechStep("Update prompts", true),
                                new ImplementationPlan.TechStep("Update PlanService", false),
                                new ImplementationPlan.TechStep("Update PromptService", false)),
                        true,
                        null,
                        null,
                        null,
                        null);
        when(planService.getCurrentPlan()).thenReturn(plan);

        String session = promptService.getSessionContext(WorkflowPhase.CODE);
        assertTrue(session.contains("## CURRENT PLAN PROGRESS"));
        assertTrue(session.contains("- [x] 0. Update prompts"));
        assertTrue(session.contains("- [ ] 1. Update PlanService"));
        assertTrue(session.contains("- [ ] 2. Update PromptService"));
    }
}
