package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock private EnvironmentService envService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(envService, eventPublisher);
    }

    private void setupCodePhase() {
        ImplementationPlan plan =
                new ImplementationPlan(
                        "T", "G", List.of(), List.of(), true, "now", "user", null, List.of());
        service.setCurrentPlan(plan);
        service.routeToPhase("CODE", "Reason");
        lenient().when(envService.getCurrentUser()).thenReturn("user");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    }

    @Test
    void testPhaseManagement() {
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        service.setCurrentPlan(
                new ImplementationPlan(
                        "T", "G", List.of(), List.of(), false, null, null, null, List.of()));

        String response = service.routeToPhase("CODE", "Reason");
        assertTrue(WorkflowService.isTransitionResponse(response));
        assertTrue(response.contains("Transition Request"));
        assertEquals(WorkflowPhase.CODE, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();

        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        assertTrue(service.getCurrentPlan().userApproved());
        assertEquals("testuser", service.getCurrentPlan().approvedBy());
    }

    @Test
    void testCodeToPlanRequiresHitlAndPreservesPlanEvenWhenAllStepsDone() {
        setupCodePhase();

        ImplementationPlan.TechStep doneStep = new ImplementationPlan.TechStep("Done step", true);
        ImplementationPlan planWithAllDone =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        List.of(),
                        List.of(doneStep),
                        true,
                        "now",
                        "user",
                        null,
                        List.of());
        service.setCurrentPlan(planWithAllDone);

        // Request PLAN from CODE (plan revision) - Requires HITL approval
        String response = service.routeToPhase("PLAN", "Need architecture revision");
        assertTrue(WorkflowService.isTransitionResponse(response));
        assertTrue(response.contains("Transition Request"));
        assertFalse(response.contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();

        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        // Must NOT complete or clear the plan during a CODE -> PLAN revision
        assertNotNull(service.getCurrentPlan());
        assertNull(service.getCurrentPlan().completedOn());
    }

    @Test
    void testReviewToPlanCompletesAndClearsPlanEvenWithIncompleteSteps() {
        setupCodePhase();
        String reviewResponse = service.routeToPhase("REVIEW", "Checking quality"); // Autonomous
        assertTrue(WorkflowService.isTransitionResponse(reviewResponse));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        ImplementationPlan.TechStep incompleteStep =
                new ImplementationPlan.TechStep("Step 1", false);
        ImplementationPlan planWithIncompleteStep =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        List.of(),
                        List.of(incompleteStep),
                        true,
                        "now",
                        "user",
                        null,
                        List.of());
        service.setCurrentPlan(planWithIncompleteStep);

        List<ImplementationPlan> updates = new ArrayList<>();
        service.addPlanListener(updates::add);

        // Request PLAN from REVIEW - Requires HITL Approval
        String planResponse = service.routeToPhase("PLAN", "Finalizing");
        assertTrue(WorkflowService.isTransitionResponse(planResponse));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();

        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertNull(service.getCurrentPlan());
        assertTrue(
                updates.stream().anyMatch(p -> p != null && p.completedOn() != null),
                "Plan should be marked completed before being cleared");
    }

    @Test
    void testAutonomousReviewTransition() {
        setupCodePhase();
        int initialLogSize = service.getCurrentPlan().handoffLog().size();

        // Request REVIEW - Autonomous transition returns structured JSON
        String response = service.routeToPhase("REVIEW", "Checking quality");
        assertTrue(WorkflowService.isTransitionResponse(response));
        assertTrue(
                response.contains("Autonomous transition"),
                "Expected autonomous transition message but got: " + response);
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        verify(eventPublisher).publishEvent(any(AutonomousTransitionEvent.class));
        assertEquals(initialLogSize + 1, service.getCurrentPlan().handoffLog().size());
    }

    @Test
    void testExploreDecoupling() {
        setupCodePhase();
        // Transition to EXPLORE should fail now because it's decoupled
        String result = service.routeToPhase("EXPLORE", "Jump");
        assertFalse(WorkflowService.isTransitionResponse(result));
        assertTrue(result.contains("Error: Cannot jump from CODE to EXPLORE"));

        service.resetWorkflow();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

    @Test
    void testReviewCompletionAndClear() {
        setupCodePhase();
        String reviewResponse = service.routeToPhase("REVIEW", "Checking quality"); // Autonomous
        assertTrue(WorkflowService.isTransitionResponse(reviewResponse));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        ImplementationPlan.TechStep step = new ImplementationPlan.TechStep("Desc", true);
        ImplementationPlan plan =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        List.of(),
                        List.of(step),
                        true,
                        "now",
                        "user",
                        null,
                        List.of());
        service.setCurrentPlan(plan);

        List<ImplementationPlan> updates = new ArrayList<>();
        service.addPlanListener(updates::add);

        // Request PLAN from REVIEW - Requires HITL Approval
        String planResponse = service.routeToPhase("PLAN", "Finalizing");
        assertTrue(WorkflowService.isTransitionResponse(planResponse));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();

        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertNull(service.getCurrentPlan());
        assertTrue(updates.stream().anyMatch(p -> p != null && p.completedOn() != null));
    }
}
