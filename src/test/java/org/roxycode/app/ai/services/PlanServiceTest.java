package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock private EnvironmentService envService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WorkflowService workflowService;

    private PlanService service;

    @BeforeEach
    void setUp() {
        workflowService = spy(new WorkflowService(envService, eventPublisher));
        service = new PlanService(workflowService);
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
    void testSemanticWorkflowMethods() {
        String reason = "Ready to build";

        doReturn(WorkflowPhase.PLAN).when(workflowService).getCurrentPhase();
        service.requestDevelopment(reason);
        verify(workflowService).routeToPhase("CODE", reason);

        doReturn(WorkflowPhase.CODE).when(workflowService).getCurrentPhase();
        service.requestReview(reason);
        verify(workflowService).routeToPhase("REVIEW", reason);

        doReturn(WorkflowPhase.REVIEW).when(workflowService).getCurrentPhase();
        service.requestFixes(reason);
        verify(workflowService, times(2)).routeToPhase("CODE", reason);

        service.requestCompletion(reason);
        verify(workflowService).routeToPhase("PLAN", reason);
    }

    @Test
    void testRequestPlanRevision() {
        doReturn(WorkflowPhase.CODE).when(workflowService).getCurrentPhase();
        String reason = "Technical blocker";
        service.requestPlanRevision(reason);
        verify(workflowService, times(1)).routeToPhase("PLAN", reason);
    }

    @Test
    void testPhaseRestrictionsEnforced() {
        // In PLAN phase, markStepCompleted and requestReview should fail
        doReturn(WorkflowPhase.PLAN).when(workflowService).getCurrentPhase();
        service.submitPlan("T", "G", List.of(), List.of("S1"));

        assertThrows(IllegalStateException.class, () -> service.markStepCompleted(0));
        assertThrows(IllegalStateException.class, () -> service.markStepIncomplete(0));
        assertThrows(IllegalStateException.class, () -> service.requestReview("done"));
        assertThrows(IllegalStateException.class, () -> service.requestFixes("fix"));
        assertThrows(IllegalStateException.class, () -> service.requestCompletion("complete"));
        assertThrows(IllegalStateException.class, () -> service.requestPlanRevision("revise"));

        // In CODE phase, submitPlan, requestDevelopment, requestFixes,
        // requestCompletion should fail
        doReturn(WorkflowPhase.CODE).when(workflowService).getCurrentPhase();
        assertDoesNotThrow(() -> service.markStepCompleted(0));
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertThrows(
                IllegalStateException.class,
                () -> service.submitPlan("T2", "G2", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> service.requestDevelopment("dev"));
        assertThrows(IllegalStateException.class, () -> service.requestFixes("fix"));
        assertThrows(IllegalStateException.class, () -> service.requestCompletion("complete"));

        // In REVIEW phase, submitPlan, requestDevelopment, requestReview,
        // requestPlanRevision should
        // fail
        doReturn(WorkflowPhase.REVIEW).when(workflowService).getCurrentPhase();
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

        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(service);

        String docs = registry.getDocumentation(WorkflowPhase.PLAN);
        assertFalse(docs.contains("approvePlan("));
        assertFalse(docs.contains("completePlan("));
        assertFalse(docs.contains("clearPlan("));

        JexlTool jexlTool = new JexlTool(registry, WorkflowPhase.PLAN);

        String approveResult = jexlTool.executeJexl("planService.approvePlan('hacker', 'now')");
        assertTrue(approveResult.contains("error"));
        assertTrue(approveResult.contains("cannot be invoked from JEXL"));
        assertFalse(service.getCurrentPlan().userApproved());

        String completeResult = jexlTool.executeJexl("planService.completePlan('now')");
        assertTrue(completeResult.contains("error"));
        assertTrue(completeResult.contains("cannot be invoked from JEXL"));
        assertNull(service.getCurrentPlan().completedOn());

        String clearResult = jexlTool.executeJexl("planService.clearPlan()");
        assertTrue(clearResult.contains("error"));
        assertTrue(clearResult.contains("cannot be invoked from JEXL"));
        assertNotNull(service.getCurrentPlan());
    }
}
