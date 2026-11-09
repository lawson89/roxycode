package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private EnvironmentService envService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(envService, eventPublisher, null);
        lenient().when(envService.getCurrentUser()).thenReturn("user");
    }

    private void setupCodePhase() {
        service.submitPlan("T", "G", new String[0], new String[]{"Step 1", "Step 2"});
        service.requestDevelopment("Reason");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    }

    private static void assertIllegalState(Executable action, String... expectedSubstrings) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, action);
        assertContainsAll(ex.getMessage(), expectedSubstrings);
    }

    @Test
    void testStepMutationsCompletionValidationAndPlanRevisionPreservation() {
        service.submitPlan("T", "G", new String[0], new String[]{"Step 1"});
        assertTrue(service.addTechnicalStep("Step 2").contains("Added technical step at index 1: 'Step 2'"));
        assertEquals(5, service.getCurrentPlan().technicalSteps().size());

        service.requestDevelopment("Ready");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        assertContainsAll(service.markStepsCompleted(0, 1), "Marked steps [0, 1] completed.");
        assertContainsAll(service.markStepsIncomplete(0, 1), "Marked steps [0, 1] incomplete.");
        service.markStepsCompleted(0, 1);
        assertIllegalState(() -> service.markStepCompleted(2), "buildToolService.compile()");
        assertIllegalState(() -> service.markStepCompleted(3), "buildToolService.runTests()");
        assertIllegalState(() -> service.markStepCompleted(4), "REVIEW phase");
        service.addHandoffLogEntry("Finished Step 1 before blocker");
        service.onFileChanged(new FileChangedEvent("src/Step1.java", FileChangedEvent.ChangeType.CREATED));

        assertTrue(service.updateTechnicalStep(0, "Step 1 (refined)").contains("Step 1 (refined)"));
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());

        assertThrows(IllegalArgumentException.class, () -> service.markStepCompleted(-1));
        assertThrows(IllegalArgumentException.class, () -> service.markStepsCompleted(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> service.addHandoffLogEntry("   "));

        service.requestPlanRevision("Split Step 2");
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        service.submitPlan("T", "G", new String[0], new String[]{"Step 1 (refined)", "Step 2a", "Step 2b"});
        ImplementationPlan revised = service.getCurrentPlan();
        assertEquals(6, revised.technicalSteps().size());
        assertTrue(revised.technicalSteps().get(0).completed());
        assertFalse(revised.technicalSteps().get(1).completed());
        assertEquals("CREATED src/Step1.java", revised.formatChangesText());
    }

    @Test
    void testWorkflowPhaseTransitionsGatesArchivingAndVerificationReset(@TempDir Path tempDir) throws Exception {
        ProjectService projectService = mock(ProjectService.class);
        Path projectRoot = tempDir.resolve("my-project");
        Path projectADataDir = tempDir.resolve("roxy-home/projects/my-project-12345678");
        Path projectBDataDir = tempDir.resolve("roxy-home/projects/other-project-87654321");
        Path archiveDir = projectADataDir.resolve("archive/plans");
        Path projectAWorkflowFile = projectADataDir.resolve(PlanService.ACTIVE_WORKFLOW_FILENAME);
        Path projectBWorkflowFile = projectBDataDir.resolve(PlanService.ACTIVE_WORKFLOW_FILENAME);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getProjectName()).thenReturn("my-project");
        when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        when(projectService.resolveProjectDataPath("archive/plans")).thenReturn(archiveDir);
        when(projectService.resolveProjectDataPath(PlanService.ACTIVE_WORKFLOW_FILENAME))
                .thenReturn(projectAWorkflowFile);

        PlanService archivingService = new PlanService(envService, eventPublisher, projectService);
        assertIllegalState(() -> archivingService.requestDevelopment("Reason"), "Implementation Plan is missing");

        archivingService.onTokenUsage(new TokenUsageEvent("qa:default", "gemini-3.5-flash", 1_048_576, 5_000, 4_000,
                1_000, 200, 50, 5_250, 0.0012));
        archivingService.onTokenUsage(new TokenUsageEvent(PlanService.TASK_CONVERSATION_ID, "gemini-3.5-flash",
                1_048_576, 20_000, 15_000, 5_000, 800, 200, 21_000, 0.0051));

        archivingService.submitPlan("Archive Feature Plan!", "Persist completed plans",
                new String[]{"Include frontmatter"}, new String[]{"Implement archiving"});
        assertEquals(1, archivingService.getCurrentPlan().usageSummary().modelCalls());
        assertEquals(21_000L, archivingService.getCurrentPlan().usageSummary().totalTokens());

        archivingService.onTokenUsage(
                new TokenUsageEvent(PlanService.TASK_CONVERSATION_ID, "gemini-3.5-flash", 1_048_576, 30_000, 25_000,
                        5_000, 1_200, 300, 31_500, WorkflowPhase.CODE, 0.0020, 0.0015, 0.0028, 0.0008, 0.0071));
        assertEquals(2, archivingService.getCurrentPlan().usageSummary().modelCalls());
        assertEquals(52_500L, archivingService.getCurrentPlan().usageSummary().totalTokens());
        assertEquals(80, archivingService.getCurrentPlan().usageSummary().cacheHitPercent());
        assertEquals(1, archivingService.getCurrentPlan().usageSummary().planPhaseUsage().modelCalls());
        assertEquals(1, archivingService.getCurrentPlan().usageSummary().codePhaseUsage().modelCalls());
        assertThrows(IllegalStateException.class, () -> archivingService.markStepCompleted(0));
        assertThrows(IllegalStateException.class, () -> archivingService.requestReview("done"));

        when(envService.getCurrentUser()).thenReturn("alice");
        assertContainsAll(archivingService.requestDevelopment("Ready for code"), "PLAN SUMMARY:",
                "Approve Plan and proceed to Code");
        archivingService.approveTransition();
        assertEquals(WorkflowPhase.CODE, archivingService.getCurrentPhase());
        assertIllegalState(() -> archivingService.routeToPhase(WorkflowPhase.EXPLORE, "Jump"),
                "Cannot jump from CODE to EXPLORE");
        assertIllegalState(() -> archivingService.requestReview("Early"), "0: Implement archiving",
                "1: Code compiles successfully", "2: Unit tests pass");

        archivingService.markStepCompleted(0);
        AtomicInteger planEvents = new AtomicInteger(0);
        doAnswer(inv -> {
            if (inv.getArgument(0) instanceof PlanUpdatedEvent) {
                planEvents.incrementAndGet();
            }
            return null;
        }).when(eventPublisher).publishEvent(any(Object.class));

        archivingService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        archivingService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        assertEquals(1, planEvents.get());
        archivingService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, true);

        archivingService.onFileChanged(new FileChangedEvent("src/Main.java", FileChangedEvent.ChangeType.MODIFIED));
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(2).completed());

        archivingService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        archivingService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, true);
        int eventsBeforeBlankChange = planEvents.get();
        archivingService.onFileChanged(new FileChangedEvent("", FileChangedEvent.ChangeType.MODIFIED));
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(2).completed());
        assertTrue(planEvents.get() > eventsBeforeBlankChange);
        assertEquals("MODIFIED src/Main.java", archivingService.getCurrentPlan().formatChangesText());

        archivingService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        archivingService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, true);
        assertTrue(archivingService.requestReview("Ready for review").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, archivingService.getCurrentPhase());

        // Case 1: requestFixes when all non-review steps are completed resets STEP_CODE_REVIEW and auto-adds a
        // remediation step
        archivingService.markStepCompleted(3);
        assertTrue(archivingService.getCurrentPlan().technicalSteps().get(3).completed());
        assertTrue(archivingService.requestFixes("Needs fix").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, archivingService.getCurrentPhase());
        assertEquals(5, archivingService.getCurrentPlan().technicalSteps().size());
        assertEquals("Address review findings: Needs fix",
                archivingService.getCurrentPlan().technicalSteps().get(1).description());
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(1).completed());
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(4).completed());
        assertIllegalState(() -> archivingService.requestReview("Try without fixing"),
                "1: Address review findings: Needs fix");

        archivingService.markStepCompleted(1);
        archivingService.requestReview("Fixed auto-added step");
        assertEquals(WorkflowPhase.REVIEW, archivingService.getCurrentPhase());

        // Case 2: addTechnicalStep and updateTechnicalStep in REVIEW phase before requestFixes does not duplicate step
        assertTrue(archivingService.addTechnicalStep("Add edge-case test").contains("Added technical step at index 2"));
        assertTrue(archivingService.updateTechnicalStep(2, "Add null-input test").contains("Add null-input test"));
        assertEquals(6, archivingService.getCurrentPlan().technicalSteps().size());
        assertTrue(archivingService.requestFixes("Missing null-input test").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, archivingService.getCurrentPhase());
        assertEquals(6, archivingService.getCurrentPlan().technicalSteps().size());
        assertFalse(archivingService.getCurrentPlan().technicalSteps().get(2).completed());

        assertIllegalState(() -> archivingService.requestCompletion("Wrong phase", "feat: x"),
                "Method 'requestCompletion' is only allowed in phase(s) [REVIEW]. Current phase: CODE");
        archivingService.markStepCompleted(2);
        archivingService.requestReview("Fixed null-input test");
        assertIllegalState(() -> archivingService.requestCompletion("All verified"), "5: Automated code review passes");

        archivingService.markStepCompleted(5);
        clearInvocations(eventPublisher);
        String commitMsg = String.join(String.valueOf((char) 10), "feat: archive plans", "",
                "- Persist completed plans to disk");
        String completionMsg = archivingService.requestCompletion("All checks passed", commitMsg);
        assertContainsAll(completionMsg, "Approve Feature Request as complete", "## Proposed Commit Message",
                "feat: archive plans");
        assertEquals(commitMsg, archivingService.getCurrentPlan().commitMessage());
        verify(eventPublisher).publishEvent(
                new WorkflowTransitionEvent(WorkflowPhase.REVIEW, WorkflowPhase.PLAN, "All checks passed", false));

        assertTrue(Files.exists(projectAWorkflowFile));
        when(envService.getCurrentUser()).thenReturn("bob");
        archivingService.approveTransition();
        assertEquals(WorkflowPhase.PLAN, archivingService.getCurrentPhase());
        assertNull(archivingService.getCurrentPlan());
        assertFalse(Files.exists(projectAWorkflowFile),
                "Completing REVIEW -> PLAN transition should delete active-workflow.json");

        List<Path> archivedFiles;
        try (Stream<Path> stream = Files.list(archiveDir)) {
            archivedFiles = stream.toList();
        }
        assertEquals(1, archivedFiles.size());
        String content = Files.readString(archivedFiles.get(0));
        assertContainsAll(content, "project: ", "my-project", "approvedBy: ", "alice", "completedBy: ", "bob",
                "totalTokens: 52500", "cachedTokens: 40000", "cacheHitPercent: 80", "planCostUsd: ", "0.0051",
                "codeCostUsd: ", "0.0071", "estimatedCostUsd: ", "0.0122", "## Token Usage & Cost",
                "### Cost by Token Category", "| **Total (2 calls)** | **52.5k (52500)** | — | **$0.01** |",
                "### Cost by Workflow Phase", "| Plan | 1 | 21.0k | $0.0051 |", "| Code | 1 | 31.5k | $0.0071 |",
                "## Proposed Commit Message", "feat: archive plans", "MODIFIED src/Main.java");
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof ArchiveUpdatedEvent));

        archivingService.submitPlan("Temp Plan", "Goal", new String[0], new String[]{"Step"});
        archivingService.requestDevelopment("Go");
        assertNotNull(archivingService.getCurrentPlan());
        assertEquals(WorkflowPhase.CODE, archivingService.getPendingPhase());
        assertEquals("Go", archivingService.getPendingReason());
        assertTrue(Files.exists(projectAWorkflowFile));

        // Switch to Project B (no saved workflow): resets in-memory state without deleting Project A's file
        when(projectService.resolveProjectDataPath(PlanService.ACTIVE_WORKFLOW_FILENAME))
                .thenReturn(projectBWorkflowFile);
        archivingService.onProjectChanged(new ProjectChangedEvent(tempDir.resolve("other-project")));
        assertNull(archivingService.getCurrentPlan());
        assertNull(archivingService.getPendingPhase());
        assertEquals(WorkflowPhase.PLAN, archivingService.getCurrentPhase());
        assertEquals(Set.of(WorkflowPhase.PLAN), archivingService.getVisitedPhases());
        assertTrue(Files.exists(projectAWorkflowFile));

        // Switch back to Project A: restores saved plan and pending transition
        clearInvocations(eventPublisher);
        when(projectService.resolveProjectDataPath(PlanService.ACTIVE_WORKFLOW_FILENAME))
                .thenReturn(projectAWorkflowFile);
        archivingService.onProjectChanged(new ProjectChangedEvent(projectRoot));
        assertNotNull(archivingService.getCurrentPlan());
        assertEquals("Temp Plan", archivingService.getCurrentPlan().title());
        assertEquals(WorkflowPhase.PLAN, archivingService.getCurrentPhase());
        assertEquals(WorkflowPhase.CODE, archivingService.getPendingPhase());
        assertEquals("Go", archivingService.getPendingReason());
        verify(eventPublisher)
                .publishEvent(new WorkflowTransitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Go", false));

        // Startup restoration via constructor and cleanup on explicit resetWorkflow()
        PlanService restartedService = new PlanService(envService, eventPublisher, projectService);
        assertNotNull(restartedService.getCurrentPlan());
        assertEquals("Temp Plan", restartedService.getCurrentPlan().title());
        assertEquals(WorkflowPhase.CODE, restartedService.getPendingPhase());

        restartedService.resetWorkflow();
        assertNull(restartedService.getCurrentPlan());
        assertNull(restartedService.getPendingPhase());
        assertFalse(Files.exists(projectAWorkflowFile));
        // Verify formatCommitMessage fallback vs explicit commitMessage and rejectCompletionAndReturnToCode
        assertEquals("", PlanService.formatCommitMessage(null));
        ImplementationPlan fallbackPlan = new ImplementationPlan("Add Fast Tests", "Run only related unit tests",
                List.of("Req 1"),
                List.of(new ImplementationPlan.TechStep("Implement runRelatedTests", true),
                        new ImplementationPlan.TechStep("Update prompt", false),
                        new ImplementationPlan.TechStep(ImplementationPlan.STEP_COMPILE, true)),
                true, null, null, null, null, List.of());
        assertEquals("Add Fast Tests\n\nRun only related unit tests\n\n- Implement runRelatedTests",
                PlanService.formatCommitMessage(fallbackPlan));
        assertEquals("feat: custom commit",
                PlanService.formatCommitMessage(fallbackPlan.withCommitMessage("  feat: custom commit  ")));

        archivingService.submitPlan("Reject Completion Plan", "Goal", new String[]{"Req"}, new String[]{"Step 1"});
        archivingService.requestDevelopment("Start");
        archivingService.approveTransition();
        archivingService.markStepCompleted(0);
        archivingService.updateVerificationStep(ImplementationPlan.STEP_COMPILE, true);
        archivingService.updateVerificationStep(ImplementationPlan.STEP_UNIT_TESTS, true);
        archivingService.requestReview("Review");
        archivingService.markStepCompleted(3);
        archivingService.requestCompletion("Done");
        assertEquals(WorkflowPhase.REVIEW, archivingService.getCurrentPhase());
        assertEquals(WorkflowPhase.PLAN, archivingService.getPendingPhase());

        archivingService.rejectCompletionAndReturnToCode("Handle null input in parser");
        assertEquals(WorkflowPhase.CODE, archivingService.getCurrentPhase());
        assertNull(archivingService.getPendingPhase());
        assertNull(archivingService.getPendingReason());
        assertTrue(archivingService.getCurrentPlan().technicalSteps().stream()
                .anyMatch(s -> !s.completed() && s.description().contains("Handle null input in parser")));
    }
}
