package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AppSettings;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock private ProjectService projectService;
    @Mock private SettingsService settingsService;
    @Mock private WorkflowService workflowService;
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
                        workflowService,
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
    void testLoadProjectContext() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        // Case 1: no .roxycode/context directory
        assertEquals("", promptService.loadProjectContext());

        // Case 2: .roxycode/context with markdown files
        Path contextDir = tempDir.resolve(".roxycode/context");
        Files.createDirectories(contextDir);
        Files.writeString(contextDir.resolve("b_architecture.md"), "Architecture guidelines");
        Files.writeString(contextDir.resolve("a_rules.md"), "Project rules");
        Files.writeString(contextDir.resolve("ignore.txt"), "Ignore me");

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
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        Path contextDir = tempDir.resolve(".roxycode/context");
        Files.createDirectories(contextDir);
        Files.writeString(contextDir.resolve("rules.md"), "CUSTOM RULES");

        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
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
    }
}
