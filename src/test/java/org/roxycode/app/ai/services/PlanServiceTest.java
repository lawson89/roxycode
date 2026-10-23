package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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
        service.submitPlan("T", "G", List.of(), List.of("Step 1", "Step 2"));
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
    void testBatchMarkStepsCompletedAndIncompleteAndReturnMessages() {
        setupCodePhase();
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertFalse(service.getCurrentPlan().technicalSteps().get(1).completed());

        String completedMsg = service.markStepsCompleted(List.of(0, 1));
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertTrue(service.getCurrentPlan().technicalSteps().get(1).completed());
        assertTrue(completedMsg.contains("Marked steps [0, 1] completed."));
        assertTrue(
                completedMsg.contains(
                        "Remaining incomplete steps: [2: Code compiles successfully, 3: Unit tests pass, 4: Automated code review passes]"));

        String singleCompletedMsg = service.markStepCompleted(2);
        assertTrue(singleCompletedMsg.contains("Marked steps [2] completed."));
        assertTrue(
                singleCompletedMsg.contains(
                        "Remaining incomplete steps: [3: Unit tests pass, 4: Automated code review passes]"));

        String incompleteMsg = service.markStepsIncomplete(List.of(0, 1));
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertFalse(service.getCurrentPlan().technicalSteps().get(1).completed());
        assertTrue(incompleteMsg.contains("Marked steps [0, 1] incomplete."));
        assertTrue(incompleteMsg.contains("0: Step 1"));
        assertTrue(incompleteMsg.contains("1: Step 2"));

        String allDoneMsg = service.markStepsCompleted(List.of(0, 1, 2, 3, 4));
        assertTrue(allDoneMsg.contains("Remaining incomplete steps: none (all steps completed)."));
    }

    @Test
    void testStepIndexBoundsAndNullPlanValidation() {
        setupCodePhase();
        // Plan has 5 steps (indices 0..4)
        IllegalArgumentException negativeEx =
                assertThrows(IllegalArgumentException.class, () -> service.markStepCompleted(-1));
        assertTrue(negativeEx.getMessage().contains("Valid 0-based indices are 0..4"));

        IllegalArgumentException outOfBoundsEx =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.markStepsCompleted(List.of(0, 5)));
        assertTrue(outOfBoundsEx.getMessage().contains("Invalid step index 5"));
        // Step 0 should not have been mutated if validation failed on index 5
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());

        assertThrows(
                IllegalArgumentException.class, () -> service.markStepsCompleted(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.markStepsCompleted(Arrays.asList(0, null)));

        String logRes = service.addHandoffLogEntry("Completed initial refactoring");
        assertEquals("Handoff log entry added.", logRes);
        assertThrows(IllegalArgumentException.class, () -> service.addHandoffLogEntry("   "));

        service.clearPlan();
        assertThrows(IllegalStateException.class, () -> service.markStepCompleted(0));
        assertThrows(IllegalStateException.class, () -> service.addHandoffLogEntry("entry"));
    }

    @Test
    void testAddAndUpdateTechnicalStepsInPlanAndCodePhases() {
        service.submitPlan("T", "G", List.of(), List.of("Step 1"));
        // Steps are: 0: Step 1, 1: Code compiles successfully, 2: Unit tests pass, 3: Automated code review passes
        String addRes = service.addTechnicalStep("Step 2");
        assertTrue(addRes.contains("Added technical step at index 1: 'Step 2'"));
        assertEquals(5, service.getCurrentPlan().technicalSteps().size());
        assertEquals("Step 1", service.getCurrentPlan().technicalSteps().get(0).description());
        assertEquals("Step 2", service.getCurrentPlan().technicalSteps().get(1).description());
        assertEquals(
                PlanService.STEP_COMPILE,
                service.getCurrentPlan().technicalSteps().get(2).description());

        // Transition to CODE phase and test updateTechnicalStep and addTechnicalStep
        service.requestDevelopment("Ready");
        when(envService.getCurrentUser()).thenReturn("user");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        service.markStepCompleted(0);
        String updateRes = service.updateTechnicalStep(0, "Step 1 (refined)");
        assertTrue(updateRes.contains("Updated technical step 0 to 'Step 1 (refined)'"));
        assertEquals(
                "Step 1 (refined)", service.getCurrentPlan().technicalSteps().get(0).description());
        assertTrue(
                service.getCurrentPlan().technicalSteps().get(0).completed(),
                "updateTechnicalStep should preserve completed status");

        String codeAddRes = service.addTechnicalStep("Step 3");
        assertTrue(codeAddRes.contains("Added technical step at index 2: 'Step 3'"));
        assertEquals(6, service.getCurrentPlan().technicalSteps().size());
        assertEquals("Step 3", service.getCurrentPlan().technicalSteps().get(2).description());

        assertThrows(
                IllegalArgumentException.class, () -> service.addTechnicalStep("  "));
        assertThrows(
                IllegalArgumentException.class, () -> service.updateTechnicalStep(0, ""));
        assertThrows(
                IllegalArgumentException.class, () -> service.updateTechnicalStep(99, "Valid"));
    }

    @Test
    void testSubmitPlanPreservesCompletedStepsAndHandoffLogOnPlanRevision() {
        setupCodePhase();
        // Complete Step 1 (index 0) and compile step (index 2)
        service.markStepsCompleted(List.of(0, 2));
        service.addHandoffLogEntry("Finished Step 1 before blocker");

        // Request plan revision: CODE -> PLAN
        service.requestPlanRevision("Split Step 2 into sub-steps");
        when(envService.getCurrentUser()).thenReturn("user");
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        int logCountBeforeSubmit = service.getCurrentPlan().handoffLog().size();
        assertTrue(logCountBeforeSubmit > 0);

        // Submit revised plan with "Step 1", "Step 2a", "Step 2b"
        service.submitPlan("T", "G", List.of(), List.of("Step 1", "Step 2a", "Step 2b"));

        ImplementationPlan revised = service.getCurrentPlan();
        assertEquals(6, revised.technicalSteps().size());
        assertTrue(
                revised.technicalSteps().get(0).completed(),
                "Previously completed 'Step 1' must remain completed");
        assertFalse(revised.technicalSteps().get(1).completed());
        assertFalse(revised.technicalSteps().get(2).completed());
        assertFalse(
                revised.technicalSteps().get(3).completed(),
                "Mandatory verification gates must be reset to incomplete on revision");
        assertEquals(
                logCountBeforeSubmit,
                revised.handoffLog().size(),
                "Handoff log history must be preserved across plan revisions");
    }

    @Test
    void testPhaseManagementAndSemanticWorkflowMethods() {
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        // Cannot requestDevelopment without a submitted plan
        IllegalStateException missingPlanEx =
                assertThrows(
                        IllegalStateException.class, () -> service.requestDevelopment("Reason"));
        assertTrue(missingPlanEx.getMessage().contains("Implementation Plan is missing"));

        service.submitPlan("T", "G", List.of("R1"), List.of("S1"));

        String devResponse = service.requestDevelopment("Ready to build");
        assertTrue(devResponse.contains("PLAN SUMMARY:"));
        assertTrue(devResponse.contains("Transition Request: Approve Plan and proceed to Code"));
        assertEquals(
                "Approve Plan and proceed to Code",
                PlanService.formatApprovalLabel(WorkflowPhase.PLAN, WorkflowPhase.CODE));
        assertEquals(
                "Approve Feature Request as complete",
                PlanService.formatApprovalLabel(WorkflowPhase.REVIEW, WorkflowPhase.PLAN));
        assertEquals(
                "Approve Plan Revision and return to Plan",
                PlanService.formatApprovalLabel(WorkflowPhase.CODE, WorkflowPhase.PLAN));
        assertEquals(WorkflowPhase.CODE, service.getPendingPhase());

        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();

        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        assertTrue(service.getCurrentPlan().userApproved());
        assertEquals("testuser", service.getCurrentPlan().approvedBy());

        // Guard: requestReview fails if pre-review steps (0, 1, 2) are incomplete
        IllegalStateException incompleteReviewEx =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.requestReview("Feature implemented"));
        assertTrue(incompleteReviewEx.getMessage().contains("0: S1"));
        assertTrue(incompleteReviewEx.getMessage().contains("1: Code compiles successfully"));
        assertTrue(incompleteReviewEx.getMessage().contains("2: Unit tests pass"));

        // Complete pre-review steps (0, 1, 2)
        service.markStepsCompleted(List.of(0, 1, 2));

        // CODE -> REVIEW (autonomous)
        String reviewRes = service.requestReview("Feature implemented");
        assertTrue(reviewRes.contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        // REVIEW -> CODE (autonomous)
        String fixesRes = service.requestFixes("Needs a fix");
        assertTrue(fixesRes.contains("Autonomous transition"));
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        // CODE -> REVIEW -> PLAN (completion)
        service.requestReview("Fixed");
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        // Guard: requestCompletion fails if Automated code review passes (step 3) is incomplete
        IllegalStateException incompleteCompletionEx =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.requestCompletion("All verified"));
        assertTrue(
                incompleteCompletionEx
                        .getMessage()
                        .contains("3: Automated code review passes"));

        service.markStepCompleted(3);
        String completionRes = service.requestCompletion("All verified");
        assertTrue(
                completionRes.contains("Transition Request: Approve Feature Request as complete"));
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
        String response = service.requestPlanRevision("Need architecture revision");
        assertTrue(
                response.contains("Transition Request: Approve Plan Revision and return to Plan"));
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
    void testReviewToPlanRejectsIncompleteStepsThenCompletesAndClearsWhenAllCompleted() {
        setupCodePhase();
        // Complete pre-review steps (0, 1, 2, 3) so CODE -> REVIEW succeeds
        service.markStepsCompleted(List.of(0, 1, 2, 3));
        String reviewResponse = service.requestReview("Checking quality"); // Autonomous
        assertTrue(reviewResponse.contains("Autonomous transition"));
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        // Step 4 ("Automated code review passes") is still incomplete -> requestCompletion must fail
        IllegalStateException incompleteEx =
                assertThrows(
                        IllegalStateException.class, () -> service.requestCompletion("Finalizing"));
        assertTrue(incompleteEx.getMessage().contains("4: Automated code review passes"));

        service.markStepCompleted(4);

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
        String planResponse = service.requestCompletion("Finalizing");
        assertTrue(planResponse.contains("Transition Request"));
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

        // If plan is unapproved, requestReview should throw IllegalStateException
        service.setCurrentPlan(
                new ImplementationPlan(
                        "Title", "Goal", List.of(), List.of(), false, null, null, null, List.of()));
        IllegalStateException unapprovedEx =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.requestReview("Checking quality"));
        assertTrue(
                unapprovedEx.getMessage().contains("An approved Implementation Plan is required"));

        // Restore approved plan
        service.approvePlan("user", "now");
        int initialLogSize = service.getCurrentPlan().handoffLog().size();

        // Request REVIEW - Autonomous transition returns String directly
        String response = service.requestReview("Checking quality");
        assertTrue(
                response.contains("Autonomous transition"),
                "Expected autonomous transition message but got: " + response);
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

        verify(eventPublisher)
                .publishEvent(
                        argThat(
                                (Object ev) ->
                                        ev instanceof WorkflowTransitionEvent wte
                                                && wte.autonomous()));
        assertEquals(initialLogSize + 1, service.getCurrentPlan().handoffLog().size());
    }

    @Test
    void testExploreDecoupling() {
        setupCodePhase();
        // Transition to EXPLORE should fail now because it's decoupled
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.routeToPhase(WorkflowPhase.EXPLORE, "Jump"));
        assertTrue(ex.getMessage().contains("Error: Cannot jump from CODE to EXPLORE"));

        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.CODE));
        service.resetWorkflow();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertEquals(java.util.Set.of(WorkflowPhase.PLAN), service.getVisitedPhases());
    }

    @Test
    void testPhaseRestrictionsEnforced() {
        // In PLAN phase, markStepCompleted and requestReview should fail
        service.submitPlan("T", "G", List.of(), List.of("S1"));

        assertThrows(IllegalStateException.class, () -> service.markStepCompleted(0));
        assertThrows(IllegalStateException.class, () -> service.markStepsCompleted(List.of(0)));
        assertThrows(IllegalStateException.class, () -> service.markStepIncomplete(0));
        assertThrows(IllegalStateException.class, () -> service.markStepsIncomplete(List.of(0)));
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
        assertDoesNotThrow(() -> service.markStepsCompleted(List.of(0, 1, 2)));
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

        // In REVIEW phase, submitPlan, requestDevelopment, requestReview, requestPlanRevision,
        // addTechnicalStep, updateTechnicalStep should fail
        assertDoesNotThrow(() -> service.markStepCompleted(3));
        assertThrows(
                IllegalStateException.class,
                () -> service.submitPlan("T3", "G3", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> service.addTechnicalStep("extra"));
        assertThrows(IllegalStateException.class, () -> service.updateTechnicalStep(0, "updated"));
        assertThrows(IllegalStateException.class, () -> service.requestDevelopment("dev"));
        assertThrows(IllegalStateException.class, () -> service.requestReview("review"));
        assertThrows(IllegalStateException.class, () -> service.requestPlanRevision("revise"));
    }

    @Test
    void testPlanServiceIsDedicatedSpringToolBeanAndNotExposedInJexl() throws Exception {
        assertNull(
                PlanService.class.getAnnotation(AgentService.class),
                "PlanService must not be annotated with @AgentService");
        assertNotNull(
                PlanService.class.getAnnotation(Service.class),
                "PlanService must be annotated with @Service");

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(service);
        assertTrue(
                registry.getServices(WorkflowPhase.PLAN).isEmpty(),
                "PlanService must not be registered in JexlServiceRegistry");

        JexlTool jexlTool = new JexlTool(registry);
        String jexlRes = jexlTool.executeJexl("planService.submitPlan('T', 'G', [], [])");
        assertTrue(jexlRes.contains("error"));

        // Verify @Tool annotations on terminal and non-terminal methods
        Method submitPlanMethod =
                PlanService.class.getMethod(
                        "submitPlan", String.class, String.class, List.class, List.class);
        Tool submitTool = submitPlanMethod.getAnnotation(Tool.class);
        assertNotNull(submitTool);
        assertFalse(submitTool.returnDirect());

        for (Method nonTerminal :
                List.of(
                        PlanService.class.getMethod("markStepCompleted", int.class),
                        PlanService.class.getMethod("markStepsCompleted", List.class),
                        PlanService.class.getMethod("markStepIncomplete", int.class),
                        PlanService.class.getMethod("markStepsIncomplete", List.class),
                        PlanService.class.getMethod("addTechnicalStep", String.class),
                        PlanService.class.getMethod("updateTechnicalStep", int.class, String.class),
                        PlanService.class.getMethod("addHandoffLogEntry", String.class))) {
            Tool toolAnn = nonTerminal.getAnnotation(Tool.class);
            assertNotNull(toolAnn, nonTerminal.getName() + " should be annotated with @Tool");
            assertFalse(toolAnn.returnDirect());
            assertEquals(String.class, nonTerminal.getReturnType());
        }

        for (String terminalMethodName :
                List.of(
                        "requestDevelopment",
                        "requestReview",
                        "requestFixes",
                        "requestCompletion",
                        "requestPlanRevision")) {
            Method m = PlanService.class.getMethod(terminalMethodName, String.class);
            Tool t = m.getAnnotation(Tool.class);
            assertNotNull(t, terminalMethodName + " should be annotated with @Tool");
            assertTrue(t.returnDirect(), terminalMethodName + " should have returnDirect = true");
            assertEquals(String.class, m.getReturnType());
        }
    }
}
