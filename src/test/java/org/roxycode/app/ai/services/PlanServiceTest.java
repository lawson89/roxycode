package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private EnvironmentService envService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(envService, eventPublisher, null, null);
        lenient().when(envService.getCurrentUser()).thenReturn("user");
    }

    private void setupCodePhase() {
        service.submitPlan("T", "G", List.of(), List.of("Step 1", "Step 2"));
        service.requestDevelopment("Reason");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    }

    private static ImplementationPlan createPlan(List<ImplementationPlan.TechStep> steps, boolean approved) {
        return new ImplementationPlan("Title", "Goal", List.of("Req 1"), steps, approved, approved ? "now" : null,
                approved ? "user" : null, null, null, List.of());
    }

    private static void assertIllegalState(Executable action, String... expectedSubstrings) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, action);
        assertContainsAll(ex.getMessage(), expectedSubstrings);
    }

    private static void assertIllegalStates(Executable... actions) {
        for (Executable action : actions)
            assertThrows(IllegalStateException.class, action);
    }

    private static void assertBadArgs(Executable... actions) {
        for (Executable action : actions)
            assertThrows(IllegalArgumentException.class, action);
    }

    @Test
    void testSubmitPlanAndNotifyListeners() {
        AtomicInteger notifications = new AtomicInteger(0);
        doAnswer(inv -> {
            if (inv.getArgument(0) instanceof PlanUpdatedEvent) {
                notifications.incrementAndGet();
            }
            return null;
        }).when(eventPublisher).publishEvent(any(Object.class));

        String res = service.submitPlan(createPlan(List.of(new ImplementationPlan.TechStep("Step 1", false)), false));
        assertEquals("Implementation plan submitted successfully with mandatory verification checklist items.", res);
        assertEquals("Title", service.getCurrentPlan().title());
        assertEquals(4, service.getCurrentPlan().technicalSteps().size());
        assertEquals(1, notifications.get());
    }

    @Test
    void testApproveAndCompletePlan() {
        service.submitPlan("Title", "Goal", List.of(), List.of("Step 1"));
        assertFalse(service.getCurrentPlan().userApproved());

        service.approvePlan("user1", "2023-01-01");
        assertTrue(service.getCurrentPlan().userApproved());
        assertEquals("user1", service.getCurrentPlan().approvedBy());

        service.completePlan("user2", "2023-01-02");
        assertEquals("user2", service.getCurrentPlan().completedBy());
        assertEquals("2023-01-02", service.getCurrentPlan().completedOn());
    }

    @Test
    void testClearPlan() {
        service.submitPlan("T", "G", List.of(), List.of("S"));
        assertNotNull(service.getCurrentPlan());
        service.clearPlan();
        assertNull(service.getCurrentPlan());
    }

    @Test
    void testBatchMarkStepsCompletedAndIncompleteAndReturnMessages() {
        setupCodePhase();
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertFalse(service.getCurrentPlan().technicalSteps().get(1).completed());

        assertContainsAll(service.markStepsCompleted(List.of(0, 1)), "Marked steps [0, 1] completed.",
                "Remaining incomplete steps: [2: Code compiles successfully, 3: Unit tests pass, 4: Automated code review passes]");
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertTrue(service.getCurrentPlan().technicalSteps().get(1).completed());

        assertContainsAll(service.markStepCompleted(2), "Marked steps [2] completed.",
                "Remaining incomplete steps: [3: Unit tests pass, 4: Automated code review passes]");

        assertContainsAll(service.markStepsIncomplete(List.of(0, 1)), "Marked steps [0, 1] incomplete.", "0: Step 1",
                "1: Step 2");
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertFalse(service.getCurrentPlan().technicalSteps().get(1).completed());

        assertTrue(service.markStepsCompleted(List.of(0, 1, 2, 3, 4))
                .contains("Remaining incomplete steps: none (all steps completed)."));
    }

    @Test
    void testStepIndexBoundsAndNullPlanValidation() {
        setupCodePhase();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.markStepCompleted(-1)).getMessage()
                .contains("Valid 0-based indices are 0..4"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.markStepsCompleted(List.of(0, 5)))
                .getMessage().contains("Invalid step index 5"));
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());

        assertBadArgs(() -> service.markStepsCompleted(List.of()),
                () -> service.markStepsCompleted(Arrays.asList(0, null)), () -> service.addHandoffLogEntry("   "));
        assertEquals("Handoff log entry added.", service.addHandoffLogEntry("Completed initial refactoring"));

        service.clearPlan();
        assertIllegalStates(() -> service.markStepCompleted(0), () -> service.addHandoffLogEntry("entry"));
    }

    @Test
    void testAddAndUpdateTechnicalStepsInPlanAndCodePhases() {
        service.submitPlan("T", "G", List.of(), List.of("Step 1"));
        assertTrue(service.addTechnicalStep("Step 2").contains("Added technical step at index 1: 'Step 2'"));
        var steps = service.getCurrentPlan().technicalSteps();
        assertEquals(5, steps.size());
        assertEquals("Step 1", steps.get(0).description());
        assertEquals("Step 2", steps.get(1).description());
        assertEquals(PlanService.STEP_COMPILE, steps.get(2).description());

        service.requestDevelopment("Ready");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        service.markStepCompleted(0);
        assertTrue(service.updateTechnicalStep(0, "Step 1 (refined)")
                .contains("Updated technical step 0 to 'Step 1 (refined)'"));
        assertEquals("Step 1 (refined)", service.getCurrentPlan().technicalSteps().get(0).description());
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());

        assertTrue(service.addTechnicalStep("Step 3").contains("Added technical step at index 2: 'Step 3'"));
        assertEquals(6, service.getCurrentPlan().technicalSteps().size());
        assertEquals("Step 3", service.getCurrentPlan().technicalSteps().get(2).description());

        assertBadArgs(() -> service.addTechnicalStep("  "), () -> service.updateTechnicalStep(0, ""),
                () -> service.updateTechnicalStep(99, "Valid"));
    }

    @Test
    void testSubmitPlanPreservesCompletedStepsAndHandoffLogOnPlanRevision() {
        setupCodePhase();
        service.markStepsCompleted(List.of(0, 2));
        service.addHandoffLogEntry("Finished Step 1 before blocker");

        service.requestPlanRevision("Split Step 2 into sub-steps");
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        int logCount = service.getCurrentPlan().handoffLog().size();
        assertTrue(logCount > 0);

        service.submitPlan("T", "G", List.of(), List.of("Step 1", "Step 2a", "Step 2b"));
        var revised = service.getCurrentPlan();
        assertEquals(6, revised.technicalSteps().size());
        assertTrue(revised.technicalSteps().get(0).completed());
        assertFalse(revised.technicalSteps().get(1).completed());
        assertFalse(revised.technicalSteps().get(2).completed());
        assertFalse(revised.technicalSteps().get(3).completed());
        assertEquals(logCount, revised.handoffLog().size());
    }

    @Test
    void testImplementationStepsContainingVerificationKeywordsAreNotMisclassifiedAsGates() {
        List<String> implSteps = List.of("Pre-compile regex patterns", "Add unit tests for FooService",
                "Update code review prompt");
        service.submitPlan("Audit Fix", "Goal", List.of(), implSteps);

        var steps = service.getCurrentPlan().technicalSteps();
        assertEquals(6, steps.size());
        assertEquals("Pre-compile regex patterns", steps.get(0).description());
        assertEquals("Add unit tests for FooService", steps.get(1).description());
        assertEquals("Update code review prompt", steps.get(2).description());
        assertEquals(PlanService.STEP_COMPILE, steps.get(3).description());
        assertEquals(PlanService.STEP_UNIT_TESTS, steps.get(4).description());
        assertEquals(PlanService.STEP_CODE_REVIEW, steps.get(5).description());

        service.requestDevelopment("Start coding");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        service.markStepsCompleted(List.of(0, 1));
        assertTrue(service.addTechnicalStep("Extra implementation step")
                .contains("Added technical step at index 3: 'Extra implementation step'"));
        assertEquals(7, service.getCurrentPlan().technicalSteps().size());
        assertEquals("Extra implementation step", service.getCurrentPlan().technicalSteps().get(3).description());

        assertIllegalState(() -> service.requestReview("Try review with incomplete impl steps"),
                "2: Update code review prompt", "3: Extra implementation step", "4: Code compiles successfully",
                "5: Unit tests pass");

        service.requestPlanRevision("Revise plan while preserving completed impl steps");
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        service.submitPlan("Audit Fix", "Goal", List.of(),
                List.of("Pre-compile regex patterns.", "Add unit tests for FooService!", "Update code review prompt",
                        "CODE COMPILES SUCCESSFULLY.", "unit tests pass!", "Automated Code Review Passes:"));
        var resubmitted = service.getCurrentPlan().technicalSteps();
        assertEquals(6, resubmitted.size());
        assertTrue(resubmitted.get(0).completed());
        assertTrue(resubmitted.get(1).completed());
        assertFalse(resubmitted.get(2).completed());
        assertFalse(resubmitted.get(3).completed());
        assertFalse(resubmitted.get(4).completed());
        assertFalse(resubmitted.get(5).completed());
    }

    @Test
    void testPhaseManagementAndSemanticWorkflowMethods() {
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertIllegalState(() -> service.requestDevelopment("Reason"), "Implementation Plan is missing");

        service.submitPlan("T", "G", List.of("R1"), List.of("S1"));
        assertContainsAll(service.requestDevelopment("Ready to build"), "PLAN SUMMARY:",
                "Transition Request: Approve Plan and proceed to Code");
        assertEquals("Approve Plan and proceed to Code",
                PlanService.formatApprovalLabel(WorkflowPhase.PLAN, WorkflowPhase.CODE));
        assertEquals("Approve Feature Request as complete",
                PlanService.formatApprovalLabel(WorkflowPhase.REVIEW, WorkflowPhase.PLAN));
        assertEquals("Approve Plan Revision and return to Plan",
                PlanService.formatApprovalLabel(WorkflowPhase.CODE, WorkflowPhase.PLAN));
        assertEquals(WorkflowPhase.CODE, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        assertTrue(service.getCurrentPlan().userApproved());
        assertEquals("testuser", service.getCurrentPlan().approvedBy());

        assertIllegalState(() -> service.requestReview("Feature implemented"), "0: S1", "1: Code compiles successfully",
                "2: Unit tests pass");

        service.markStepsCompleted(List.of(0, 1, 2));
        assertTrue(service.requestReview("Feature implemented").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        assertTrue(service.requestFixes("Needs a fix").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        service.requestReview("Fixed");
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        assertIllegalState(() -> service.requestCompletion("All verified"), "3: Automated code review passes");

        service.markStepCompleted(3);
        assertTrue(service.requestCompletion("All verified")
                .contains("Transition Request: Approve Feature Request as complete"));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
    }

    @Test
    void testRequestPlanRevisionAndCodeToPlanPreservesPlan() {
        setupCodePhase();
        service.setCurrentPlan(createPlan(List.of(new ImplementationPlan.TechStep("Done step", true)), true));

        String response = service.requestPlanRevision("Need architecture revision");
        assertTrue(response.contains("Transition Request: Approve Plan Revision and return to Plan"));
        assertFalse(response.contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());

        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        assertNotNull(service.getCurrentPlan());
        assertNull(service.getCurrentPlan().completedOn());
    }

    @Test
    void testReviewToPlanRejectsIncompleteStepsThenCompletesAndClearsWhenAllCompleted() {
        setupCodePhase();
        service.markStepsCompleted(List.of(0, 1, 2, 3));
        assertTrue(service.requestReview("Checking quality").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        assertIllegalState(() -> service.requestCompletion("Finalizing"), "4: Automated code review passes");

        service.markStepCompleted(4);
        List<ImplementationPlan> updates = new ArrayList<>();
        doAnswer(inv -> {
            if (inv.getArgument(0) instanceof PlanUpdatedEvent pue) {
                updates.add(pue.plan());
            }
            return null;
        }).when(eventPublisher).publishEvent(any(Object.class));

        assertTrue(service.requestCompletion("Finalizing").contains("Transition Request"));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());

        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertNull(service.getCurrentPlan());
        assertTrue(
                updates.stream().anyMatch(p -> p != null && "user".equals(p.completedBy()) && p.completedOn() != null));
    }

    @Test
    void testReviewToPlanArchivesCompletedPlanWithHitlMetadataAndFrontmatter(@TempDir Path tempDir) throws Exception {
        ProjectService projectService = mock(ProjectService.class);
        GitService gitService = mock(GitService.class);
        Path projectRoot = tempDir.resolve("my-project");
        Path archiveDir = tempDir.resolve("roxy-home/projects/my-project-12345678/archive/plans");

        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getProjectName()).thenReturn("my-project");
        when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        when(projectService.resolveProjectDataPath("archive/plans")).thenReturn(archiveDir);
        when(gitService.getStatus()).thenReturn(" M src/Main.java\n");

        PlanService archivingService = new PlanService(envService, eventPublisher, projectService, gitService);
        archivingService.submitPlan("Archive Feature Plan!", "Persist completed plans",
                List.of("Include frontmatter", "Include approvals"), List.of("Implement archiving"));

        when(envService.getCurrentUser()).thenReturn("alice");
        archivingService.requestDevelopment("Ready for code");
        archivingService.approveTransition();
        assertEquals(WorkflowPhase.CODE, archivingService.getCurrentPhase());

        archivingService.markStepsCompleted(List.of(0, 1, 2));
        archivingService.requestReview("Ready for review");
        assertEquals(WorkflowPhase.REVIEW, archivingService.getCurrentPhase());

        archivingService.markStepCompleted(3);
        archivingService.requestCompletion("All checks passed");

        when(envService.getCurrentUser()).thenReturn("bob");
        archivingService.approveTransition();

        assertEquals(WorkflowPhase.PLAN, archivingService.getCurrentPhase());
        assertNull(archivingService.getCurrentPlan());
        assertTrue(Files.isDirectory(archiveDir));

        List<Path> archivedFiles;
        try (Stream<Path> stream = Files.list(archiveDir)) {
            archivedFiles = stream.toList();
        }
        assertEquals(1, archivedFiles.size());
        Path archivedFile = archivedFiles.get(0);
        String fileName = archivedFile.getFileName().toString();
        assertTrue(fileName.endsWith("-archive-feature-plan.md"));

        String content = Files.readString(archivedFile);
        assertTrue(content.startsWith("---\n"));
        assertContainsAll(content, "project: ", "my-project", "projectPath: ",
                projectRoot.toAbsolutePath().normalize().toString(), "title: ", "Archive Feature Plan!",
                "userApproved: true\n", "approvedBy: ", "alice", "completedBy: ", "bob",
                "# Implementation Plan: Archive Feature Plan!", "## Approvals",
                "- **Move to Code:** Approved by alice on ", "- **Final Acceptance:** Accepted by bob on ",
                "## Handoff Log", "REVIEW -> PLAN (User Approved): All checks passed", "## Changes", "M src/Main.java");
        verify(eventPublisher).publishEvent(
                argThat((Object ev) -> ev instanceof ArchiveUpdatedEvent aue && fileName.equals(aue.filename())));
    }

    @Test
    void testAutonomousReviewTransitionAndUnapprovedPlanGuard() {
        setupCodePhase();
        service.setCurrentPlan(createPlan(List.of(), false));
        assertIllegalState(() -> service.requestReview("Checking quality"),
                "An approved Implementation Plan is required");

        service.approvePlan("user", "now");
        int initialLogSize = service.getCurrentPlan().handoffLog().size();

        assertTrue(service.requestReview("Checking quality").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());
        verify(eventPublisher)
                .publishEvent(argThat((Object ev) -> ev instanceof WorkflowTransitionEvent wte && wte.autonomous()));
        assertEquals(initialLogSize + 1, service.getCurrentPlan().handoffLog().size());
    }

    @Test
    void testExploreDecoupling() {
        setupCodePhase();
        assertIllegalState(() -> service.routeToPhase(WorkflowPhase.EXPLORE, "Jump"),
                "Error: Cannot jump from CODE to EXPLORE");

        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.CODE));
        service.resetWorkflow();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertEquals(Set.of(WorkflowPhase.PLAN), service.getVisitedPhases());
    }

    @Test
    void testPhaseRestrictionsEnforced() {
        service.submitPlan("T", "G", List.of(), List.of("S1"));
        assertIllegalStates(() -> service.markStepCompleted(0), () -> service.markStepsCompleted(List.of(0)),
                () -> service.markStepIncomplete(0), () -> service.markStepsIncomplete(List.of(0)),
                () -> service.requestReview("done"), () -> service.requestFixes("fix"),
                () -> service.requestCompletion("complete"), () -> service.requestPlanRevision("revise"));

        service.requestDevelopment("Ready");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        assertDoesNotThrow(() -> service.markStepsCompleted(List.of(0, 1, 2)));
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertIllegalStates(() -> service.submitPlan("T2", "G2", List.of(), List.of()),
                () -> service.requestDevelopment("dev"), () -> service.requestFixes("fix"),
                () -> service.requestCompletion("complete"));

        service.requestReview("Ready for review");
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        assertDoesNotThrow(() -> service.markStepCompleted(3));
        assertIllegalStates(() -> service.submitPlan("T3", "G3", List.of(), List.of()),
                () -> service.addTechnicalStep("extra"), () -> service.updateTechnicalStep(0, "updated"),
                () -> service.requestDevelopment("dev"), () -> service.requestReview("review"),
                () -> service.requestPlanRevision("revise"));
    }

    @Test
    void testPlanServiceIsExposedInJexlWithJsArrayLiteralsAndPhaseFiltering() throws Exception {
        AgentService agentService = PlanService.class.getAnnotation(AgentService.class);
        assertNotNull(agentService);
        assertEquals("planService", agentService.value());
        assertArrayEquals(new String[]{"PLAN", "CODE", "REVIEW"}, agentService.phases());
        assertNull(PlanService.class.getDeclaredAnnotation(Service.class));

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(service);
        assertTrue(registry.getServices(WorkflowPhase.EXPLORE).isEmpty());
        assertSame(service, registry.getServices(WorkflowPhase.PLAN).get("planService"));
        assertSame(service, registry.getServices(WorkflowPhase.CODE).get("planService"));
        assertSame(service, registry.getServices(WorkflowPhase.REVIEW).get("planService"));

        JexlTool jexlTool = new JexlTool(registry, null, null, null);
        String planResult = jexlTool.executeJexl("planService.submitPlan('T', 'G', ['Req 1'], ['Step 1', 'Step 2'])",
                new ToolContext(new TurnToolContext("conv", WorkflowPhase.PLAN, new AtomicInteger()).toMap()));
        assertTrue(planResult.contains("Implementation plan submitted successfully"));

        String devResult = jexlTool.executeJexl("planService.requestDevelopment('Ready')",
                new ToolContext(new TurnToolContext("conv", WorkflowPhase.PLAN, new AtomicInteger()).toMap()));
        assertTrue(devResult.contains("Transition Request"));
        service.approveTransition();

        String markResult = jexlTool.executeJexl("planService.markStepsCompleted([0, 1])",
                new ToolContext(new TurnToolContext("conv", WorkflowPhase.CODE, new AtomicInteger()).toMap()));
        assertTrue(markResult.contains("Marked steps [0, 1] completed."));
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertTrue(service.getCurrentPlan().technicalSteps().get(1).completed());

        String unmarkResult = jexlTool.executeJexl("planService.markStepsIncomplete([1])",
                new ToolContext(new TurnToolContext("conv", WorkflowPhase.CODE, new AtomicInteger()).toMap()));
        assertTrue(unmarkResult.contains("Marked steps [1] incomplete."));
        assertFalse(service.getCurrentPlan().technicalSteps().get(1).completed());

        for (Method m : List.of(PlanService.class.getMethod("markStepCompleted", int.class),
                PlanService.class.getMethod("markStepsCompleted", int[].class),
                PlanService.class.getMethod("markStepsCompleted", List.class),
                PlanService.class.getMethod("markStepIncomplete", int.class),
                PlanService.class.getMethod("markStepsIncomplete", int[].class),
                PlanService.class.getMethod("markStepsIncomplete", List.class),
                PlanService.class.getMethod("addTechnicalStep", String.class),
                PlanService.class.getMethod("updateTechnicalStep", int.class, String.class),
                PlanService.class.getMethod("addHandoffLogEntry", String.class))) {
            assertNotNull(m.getAnnotation(AgentDoc.class));
            assertEquals(String.class, m.getReturnType());
        }

        for (String name : List.of("requestDevelopment", "requestReview", "requestFixes", "requestCompletion",
                "requestPlanRevision")) {
            Method m = PlanService.class.getMethod(name, String.class);
            assertNotNull(m.getAnnotation(AgentDoc.class));
            assertEquals(String.class, m.getReturnType());
        }
    }
}
