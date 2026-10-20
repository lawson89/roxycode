package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock private EnvironmentService envService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(envService, eventPublisher);
    }

    private void setupCodePhase() {
        service.submitPlan("T", "G", List.of(), List.of("Step 1"));
        service.requestDevelopment("Reason");
        lenient().when(envService.getCurrentUser()).thenReturn("user");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    }

    @Test
    void testSubmitPlanAndNotifyListeners() {
        AtomicInteger notifications = new AtomicInteger(0);
        doAnswer(
                        inv -> {
                            if (inv.getArgument(0) instanceof PlanUpdatedEvent) {
                                notifications.incrementAndGet();
                            }
                            return null;
                        })
                .when(eventPublisher)
                .publishEvent(any(Object.class));

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        List.of("Req 1"),
                        List.of(new ImplementationPlan.TechStep("Step 1", false)),
                        false,
                        null,
                        null,
                        null,
                        null);
        String res = service.submitPlan(plan);

        assertEquals(
                "Implementation plan submitted successfully with mandatory verification checklist items.",
                res);
        assertNotNull(service.getCurrentPlan());
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

        service.completePlan("2023-01-02");
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
    void testPhaseManagementAndSemanticWorkflowMethods() {
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        // Cannot requestDevelopment without a submitted plan
        Object missingPlanRes = service.requestDevelopment("Reason");
        assertFalse(PlanService.isTransitionResponse(missingPlanRes));
        assertTrue(missingPlanRes.toString().contains("Implementation Plan is missing"));

        service.submitPlan("T", "G", List.of("R1"), List.of("S1"));

        Object devResponse = service.requestDevelopment("Ready to build");
        assertTrue(PlanService.isTransitionResponse(devResponse));
        PlanService.TransitionResult transitionResult =
                assertInstanceOf(PlanService.TransitionResult.class, devResponse);
        assertTrue(transitionResult.markdown().contains("PLAN SUMMARY:"));
        assertTrue(transitionResult.markdown().contains("Transition Request"));
        assertEquals(transitionResult.markdown(), transitionResult.toString());
        assertEquals(WorkflowPhase.CODE, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();

        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        assertTrue(service.getCurrentPlan().userApproved());
        assertEquals("testuser", service.getCurrentPlan().approvedBy());

        // CODE -> REVIEW (autonomous)
        Object reviewRes = service.requestReview("Feature implemented");
        assertTrue(PlanService.isTransitionResponse(reviewRes));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        // REVIEW -> CODE (autonomous)
        Object fixesRes = service.requestFixes("Needs a fix");
        assertTrue(PlanService.isTransitionResponse(fixesRes));
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        // CODE -> REVIEW -> PLAN (completion)
        service.requestReview("Fixed");
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());
        Object completionRes = service.requestCompletion("All verified");
        assertTrue(PlanService.isTransitionResponse(completionRes));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
    }

    @Test
    void testRequestPlanRevisionAndCodeToPlanPreservesPlan() {
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
        Object response = service.requestPlanRevision("Need architecture revision");
        assertTrue(PlanService.isTransitionResponse(response));
        assertTrue(response.toString().contains("Transition Request"));
        assertFalse(response.toString().contains("Autonomous transition"));
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
        Object reviewResponse = service.requestReview("Checking quality"); // Autonomous
        assertTrue(PlanService.isTransitionResponse(reviewResponse));
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
        doAnswer(
                        inv -> {
                            Object ev = inv.getArgument(0);
                            if (ev instanceof PlanUpdatedEvent pue) {
                                updates.add(pue.plan());
                            }
                            return null;
                        })
                .when(eventPublisher)
                .publishEvent(any(Object.class));

        // Request PLAN from REVIEW - Requires HITL Approval
        Object planResponse = service.requestCompletion("Finalizing");
        assertTrue(PlanService.isTransitionResponse(planResponse));
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
    void testAutonomousReviewTransitionAndUnapprovedPlanGuard() {
        setupCodePhase();

        // If plan is unapproved, requestReview should return an error
        service.setCurrentPlan(
                new ImplementationPlan(
                        "Title", "Goal", List.of(), List.of(), false, null, null, null, List.of()));
        Object unapprovedRes = service.requestReview("Checking quality");
        assertFalse(PlanService.isTransitionResponse(unapprovedRes));
        assertTrue(
                unapprovedRes.toString().contains("An approved Implementation Plan is required"));

        // Restore approved plan
        service.approvePlan("user", "now");
        int initialLogSize = service.getCurrentPlan().handoffLog().size();

        // Request REVIEW - Autonomous transition returns TransitionResult
        Object response = service.requestReview("Checking quality");
        assertTrue(PlanService.isTransitionResponse(response));
        assertTrue(
                response.toString().contains("Autonomous transition"),
                "Expected autonomous transition message but got: " + response);
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        verify(eventPublisher).publishEvent(any(AutonomousTransitionEvent.class));
        assertEquals(initialLogSize + 1, service.getCurrentPlan().handoffLog().size());
    }

    @Test
    void testExploreDecoupling() {
        setupCodePhase();
        // Transition to EXPLORE should fail now because it's decoupled
        Object result = service.routeToPhase("EXPLORE", "Jump");
        assertFalse(PlanService.isTransitionResponse(result));
        assertInstanceOf(String.class, result);
        assertTrue(result.toString().contains("Error: Cannot jump from CODE to EXPLORE"));

        service.resetWorkflow();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

    @Test
    void testPhaseRestrictionsEnforced() {
        // In PLAN phase, markStepCompleted and requestReview should fail
        service.submitPlan("T", "G", List.of(), List.of("S1"));

        assertThrows(IllegalStateException.class, () -> service.markStepCompleted(0));
        assertThrows(IllegalStateException.class, () -> service.markStepIncomplete(0));
        assertThrows(IllegalStateException.class, () -> service.requestReview("done"));
        assertThrows(IllegalStateException.class, () -> service.requestFixes("fix"));
        assertThrows(IllegalStateException.class, () -> service.requestCompletion("complete"));
        assertThrows(IllegalStateException.class, () -> service.requestPlanRevision("revise"));

        // Transition to CODE phase
        service.requestDevelopment("Ready");
        when(envService.getCurrentUser()).thenReturn("user");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        // In CODE phase, submitPlan, requestDevelopment, requestFixes, requestCompletion should
        // fail
        assertDoesNotThrow(() -> service.markStepCompleted(0));
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertThrows(
                IllegalStateException.class,
                () -> service.submitPlan("T2", "G2", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> service.requestDevelopment("dev"));
        assertThrows(IllegalStateException.class, () -> service.requestFixes("fix"));
        assertThrows(IllegalStateException.class, () -> service.requestCompletion("complete"));

        // Transition to REVIEW phase
        service.requestReview("Ready for review");
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        // In REVIEW phase, submitPlan, requestDevelopment, requestReview, requestPlanRevision
        // should fail
        assertDoesNotThrow(() -> service.markStepCompleted(1));
        assertThrows(
                IllegalStateException.class,
                () -> service.submitPlan("T3", "G3", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> service.requestDevelopment("dev"));
        assertThrows(IllegalStateException.class, () -> service.requestReview("review"));
        assertThrows(IllegalStateException.class, () -> service.requestPlanRevision("revise"));
    }

    @Test
    void testInternalLifecycleMethodsBlockedFromJexlAndOmittedFromDocs() {
        service.submitPlan("T", "G", List.of(), List.of("S1"));
        service.requestDevelopment("Ready");

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(service);

        String docs = registry.getDocumentation(WorkflowPhase.PLAN);
        assertFalse(docs.contains("approvePlan("));
        assertFalse(docs.contains("completePlan("));
        assertFalse(docs.contains("clearPlan("));
        assertFalse(docs.contains("approveTransition("));
        assertFalse(docs.contains("rejectTransition("));
        assertFalse(docs.contains("resetWorkflow("));
        assertFalse(docs.contains("routeToPhase("));

        JexlTool jexlTool = new JexlTool(registry, WorkflowPhase.PLAN);

        String approveResult = jexlTool.executeJexl("planService.approvePlan('hacker', 'now')");
        assertTrue(approveResult.contains("error"));
        assertFalse(service.getCurrentPlan().userApproved());

        String approveTransResult = jexlTool.executeJexl("planService.approveTransition()");
        assertTrue(approveTransResult.contains("error"));
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        String completeResult = jexlTool.executeJexl("planService.completePlan('now')");
        assertTrue(completeResult.contains("error"));
        assertNull(service.getCurrentPlan().completedOn());

        String clearResult = jexlTool.executeJexl("planService.clearPlan()");
        assertTrue(clearResult.contains("error"));
        assertNotNull(service.getCurrentPlan());
    }
}
