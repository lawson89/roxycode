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
        Path archiveDir = tempDir.resolve("roxy-home/projects/my-project-12345678/archive/plans");
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getProjectName()).thenReturn("my-project");
        when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        when(projectService.resolveProjectDataPath("archive/plans")).thenReturn(archiveDir);

        PlanService archivingService = new PlanService(envService, eventPublisher, projectService);
        assertIllegalState(() -> archivingService.requestDevelopment("Reason"), "Implementation Plan is missing");

        archivingService.submitPlan("Archive Feature Plan!", "Persist completed plans",
                new String[]{"Include frontmatter"}, new String[]{"Implement archiving"});
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

        assertTrue(archivingService.requestFixes("Needs fix").contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, archivingService.getCurrentPhase());
        archivingService.requestReview("Fixed");
        assertIllegalState(() -> archivingService.requestCompletion("All verified"), "3: Automated code review passes");

        archivingService.markStepCompleted(3);
        assertTrue(archivingService.requestCompletion("All checks passed")
                .contains("Approve Feature Request as complete"));

        when(envService.getCurrentUser()).thenReturn("bob");
        archivingService.approveTransition();
        assertEquals(WorkflowPhase.PLAN, archivingService.getCurrentPhase());
        assertNull(archivingService.getCurrentPlan());

        List<Path> archivedFiles;
        try (Stream<Path> stream = Files.list(archiveDir)) {
            archivedFiles = stream.toList();
        }
        assertEquals(1, archivedFiles.size());
        String content = Files.readString(archivedFiles.get(0));
        assertContainsAll(content, "project: ", "my-project", "approvedBy: ", "alice", "completedBy: ", "bob",
                "MODIFIED src/Main.java");
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof ArchiveUpdatedEvent));

        archivingService.submitPlan("Temp Plan", "Goal", new String[0], new String[]{"Step"});
        archivingService.requestDevelopment("Go");
        assertNotNull(archivingService.getCurrentPlan());
        assertEquals(WorkflowPhase.CODE, archivingService.getPendingPhase());

        archivingService.onProjectChanged(new ProjectChangedEvent(projectRoot));
        assertNull(archivingService.getCurrentPlan());
        assertNull(archivingService.getPendingPhase());
        assertEquals(WorkflowPhase.PLAN, archivingService.getCurrentPhase());
        assertEquals(Set.of(WorkflowPhase.PLAN), archivingService.getVisitedPhases());
    }
}
