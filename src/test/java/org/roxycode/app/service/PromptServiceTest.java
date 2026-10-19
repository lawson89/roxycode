package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;

class PromptServiceTest {

  private PromptService promptService;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    promptService = new PromptService(null, null);
  }

  @Test
  void testLoadCoreWorkflowPrompt() {
    String prompt = promptService.loadCoreWorkflowPrompt();
    assertNotNull(prompt);
    assertTrue(prompt.contains("RoxyCode Workflow Protocol"));
  }

  /**
   * Verifies that the core workflow prompt documents RoxyCode's 4 phases and actual services, while
   * excluding references to external orchestrator APIs.
   */
  @Test
  void testCoreWorkflowPromptReflectsRoxyCodePhasesAndServices() {
    String prompt = promptService.loadCoreWorkflowPrompt();
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
    String prompt = promptService.loadReviewPrompt();
    assertNotNull(prompt);
    assertTrue(prompt.contains("REVIEW"));
    assertTrue(prompt.contains("PHASE"));
  }

  @Test
  void testLoadExplorePrompt() {
    String prompt = promptService.loadExplorePrompt();
    assertNotNull(prompt);
    assertTrue(prompt.contains("EXPLORE phase"));
  }

  @Test
  void testLoadPlanPrompt() {
    String prompt = promptService.loadPlanPrompt();
    assertNotNull(prompt);
    assertTrue(prompt.contains("ANALYST"));
    assertTrue(prompt.contains("PLANNING"));
    assertTrue(prompt.contains("Requirements Gathering & Clarification"));
  }

  @Test
  void testLoadCodePrompt() {
    String prompt = promptService.loadCodePrompt();
    assertNotNull(prompt);
    assertTrue(prompt.contains("CODER"));
    assertTrue(prompt.contains("ACTING"));
  }

  @Test
  void testLoadPhasePrompt() {
    assertEquals(
        promptService.loadExplorePrompt(), promptService.loadPhasePrompt(WorkflowPhase.EXPLORE));
    assertEquals(promptService.loadPlanPrompt(), promptService.loadPhasePrompt(WorkflowPhase.PLAN));
    assertEquals(promptService.loadCodePrompt(), promptService.loadPhasePrompt(WorkflowPhase.CODE));
    assertEquals(
        promptService.loadReviewPrompt(), promptService.loadPhasePrompt(WorkflowPhase.REVIEW));
  }

  @Test
  void testLoadProjectContext() throws IOException {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(true);
    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

    PromptService serviceWithProject = new PromptService(projectService, null);
    // Case 1: no .roxycode/context directory
    assertEquals("", serviceWithProject.loadProjectContext());

    // Case 2: .roxycode/context with markdown files
    Path contextDir = tempDir.resolve(".roxycode/context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("b_architecture.md"), "Architecture guidelines");
    Files.writeString(contextDir.resolve("a_rules.md"), "Project rules");
    Files.writeString(contextDir.resolve("ignore.txt"), "Ignore me");

    String context = serviceWithProject.loadProjectContext();
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
    SettingsService settingsService = mock(SettingsService.class);
    org.roxycode.app.model.AppSettings settings = new org.roxycode.app.model.AppSettings();
    settings.setMaxAgentToolTurns(42);
    when(settingsService.getSettings()).thenReturn(settings);

    PromptService serviceWithSettings = new PromptService(null, settingsService);
    String prompt = serviceWithSettings.generateExploreSystemPrompt();

    assertNotNull(prompt);
    assertTrue(prompt.contains("42"));
    assertFalse(prompt.contains("${maxTurns}"));
  }

  @Test
  void testGetStableSessionAndSystemPrompt() throws IOException {
    ProjectService projectService = mock(ProjectService.class);
    when(projectService.hasActiveProject()).thenReturn(true);
    when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
    Path contextDir = tempDir.resolve(".roxycode/context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("rules.md"), "CUSTOM RULES");

    SettingsService settingsService = mock(SettingsService.class);
    WorkflowService workflowService = mock(WorkflowService.class);
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);

    JexlServiceRegistry jexlServiceRegistry = mock(JexlServiceRegistry.class);
    when(jexlServiceRegistry.getDocumentation(WorkflowPhase.CODE)).thenReturn("JEXL DOCS");

    GitService gitService = mock(GitService.class);
    when(gitService.getStatus()).thenReturn("M file.java");

    ProjectAnalysisService projectAnalysisService = mock(ProjectAnalysisService.class);
    when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");

    RepoMapPackerService repoMapPackerService = mock(RepoMapPackerService.class);
    when(repoMapPackerService.generateRepoMap())
        .thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));

    PromptService fullPromptService =
        new PromptService(
            projectService,
            settingsService,
            workflowService,
            jexlServiceRegistry,
            gitService,
            projectAnalysisService,
            repoMapPackerService);

    String stable = fullPromptService.getStableContext();
    assertTrue(stable.contains("RoxyCode Workflow Protocol"));
    assertTrue(stable.contains("## JEXL CONTEXT"));
    assertTrue(stable.contains("CUSTOM RULES"));

    String session = fullPromptService.getSessionContext();
    assertTrue(session.contains("DOMINANT LANGUAGE: Java"));
    assertTrue(session.contains("CURRENT PHASE: CODE"));
    assertTrue(session.contains("Senior Developer"));
    assertTrue(session.contains("JEXL DOCS"));
    assertTrue(session.contains("M file.java"));

    String fullSystemPrompt =
        fullPromptService.buildSystemPrompt(WorkflowPhase.CODE, "EXTRA INSTRUCTIONS");
    assertTrue(fullSystemPrompt.contains(stable));
    assertTrue(fullSystemPrompt.contains("REPO MAP CONTENT"));
    assertTrue(fullSystemPrompt.contains(session));
    assertTrue(fullSystemPrompt.contains("EXTRA INSTRUCTIONS"));
  }
}
