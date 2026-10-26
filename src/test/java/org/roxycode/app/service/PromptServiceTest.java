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
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.PromptService.ContextFileItem;

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
    void testGlobalAndProjectContextFoldersAndTopLevelTextDiscovery() throws IOException {
        Path projectDataDir = tempDir.resolve("project-data");
        Path projectRoot = tempDir.resolve("project-root");
        Files.createDirectories(projectRoot);
        when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));

        AppSettings settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);

        // Initially no folders configured
        assertEquals("", promptService.loadGlobalContext());
        assertEquals("", promptService.loadProjectContext());
        assertTrue(promptService.listGlobalContextFiles().isEmpty());
        assertTrue(promptService.listProjectContextFiles().isEmpty());

        // Set up global context folder with top-level text files, hidden file, binary file, and
        // subdir
        Path globalDir = tempDir.resolve("global-context");
        Files.createDirectories(globalDir);
        Files.writeString(globalDir.resolve("b_global.txt"), "Global B text");
        Files.writeString(globalDir.resolve("a_global.md"), "Global A markdown");
        Files.writeString(globalDir.resolve(".hidden.md"), "Hidden file");
        Files.write(globalDir.resolve("binary.bin"), new byte[] {0x01, 0x00, 0x02});
        Path nestedGlobalDir = globalDir.resolve("nested");
        Files.createDirectories(nestedGlobalDir);
        Files.writeString(nestedGlobalDir.resolve("sub.md"), "Nested should be ignored");

        promptService.setGlobalContextFolders(
                List.of("  " + globalDir.toString() + "  ", globalDir.toString()));
        verify(settingsService).saveSettings();
        assertEquals(List.of(globalDir.toString()), promptService.getGlobalContextFolders());

        List<ContextFileItem> globalFiles = promptService.listGlobalContextFiles();
        assertEquals(2, globalFiles.size());
        assertEquals("a_global.md", globalFiles.get(0).fileName());
        assertEquals("b_global.txt", globalFiles.get(1).fileName());
        assertEquals(
                "Global A markdown", promptService.readContextFile(globalFiles.get(0).filePath()));

        // Set up project context folder (relative to projectRoot and absolute)
        Path projDocsDir = projectRoot.resolve("docs");
        Files.createDirectories(projDocsDir);
        Files.writeString(projDocsDir.resolve("z_spec.json"), "{spec: true}");
        Files.writeString(projDocsDir.resolve("a_architecture.md"), "Architecture guidelines");
        Files.write(projDocsDir.resolve("image.png"), new byte[] {(byte) 0x89, 0x50, 0x00, 0x47});
        Files.createDirectories(projDocsDir.resolve("subdir"));
        Files.writeString(projDocsDir.resolve("subdir/ignored.md"), "Subdir ignored");

        promptService.setProjectContextFolders(List.of("docs", "docs"));
        assertEquals(List.of("docs"), promptService.getProjectContextFolders());

        List<ContextFileItem> projectFiles = promptService.listProjectContextFiles();
        assertEquals(2, projectFiles.size());
        assertEquals("a_architecture.md", projectFiles.get(0).fileName());
        assertEquals("z_spec.json", projectFiles.get(1).fileName());
        assertEquals(
                "Architecture guidelines",
                promptService.readContextFile(projectFiles.get(0).filePath()));

        String globalContext = promptService.loadGlobalContext();
        assertTrue(globalContext.contains("## Global Context: a_global.md\nGlobal A markdown"));
        assertTrue(globalContext.contains("## Global Context: b_global.txt\nGlobal B text"));
        assertFalse(globalContext.contains("Hidden file"));
        assertFalse(globalContext.contains("Nested should be ignored"));

        String projectContext = promptService.loadProjectContext();
        assertTrue(
                projectContext.contains(
                        "## Project Context: a_architecture.md\nArchitecture guidelines"));
        assertTrue(projectContext.contains("## Project Context: z_spec.json\n{spec: true}"));
        assertFalse(projectContext.contains("Subdir ignored"));

        // Alphabetical ordering check
        assertTrue(
                projectContext.indexOf("a_architecture.md") < projectContext.indexOf("z_spec.json"),
                "Project context files should be sorted alphabetically");

        // Validation on readContextFile(Path)
        Path outsideFile = tempDir.resolve("outside.md");
        Files.writeString(outsideFile, "Outside");
        assertThrows(
                IllegalArgumentException.class, () -> promptService.readContextFile(outsideFile));
        assertThrows(
                IllegalArgumentException.class,
                () -> promptService.readContextFile(globalDir.resolve(".hidden.md")));
        assertThrows(
                IllegalArgumentException.class,
                () -> promptService.readContextFile(globalDir.resolve("binary.bin")));
        assertThrows(IllegalArgumentException.class, () -> promptService.readContextFile(null));
    }

    @Test
    void testLoadExplorePhasePromptSubstitutesMaxTurns() {
        AppSettings settings = new AppSettings();
        settings.setMaxAgentToolTurns(42);
        when(settingsService.getSettings()).thenReturn(settings);

        String prompt = promptService.loadPhasePrompt(WorkflowPhase.EXPLORE);

        assertNotNull(prompt);
        assertTrue(prompt.contains("42"));
        assertFalse(prompt.contains("${maxTurns}"));
    }

    @Test
    void testGetStableSessionAndSystemPrompt() throws IOException {
        Path projectDataDir = tempDir.resolve("project-data");
        Path globalDir = tempDir.resolve("global-rules");
        Path projectRulesDir = tempDir.resolve("project-rules");
        Files.createDirectories(globalDir);
        Files.createDirectories(projectRulesDir);
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
        when(repoMapPackerService.generateRepoMap())
                .thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));

        String stable = promptService.getStableContext();
        assertTrue(stable.contains("RoxyCode Workflow Protocol"));
        assertTrue(stable.contains("## JEXL CONTEXT"));
        assertTrue(stable.contains("## Global Context: global.md\nGLOBAL RULES"));
        assertTrue(stable.contains("## Project Context: rules.md\nCUSTOM RULES"));

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
