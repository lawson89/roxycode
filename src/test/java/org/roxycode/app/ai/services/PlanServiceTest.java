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
import org.roxycode.app.model.ImplementationPlan;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

  @Mock private WorkflowService workflowService;

  private PlanService service;

  @BeforeEach
  void setUp() {
    lenient().when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
    service = new PlanService(workflowService);
  }

  @Test
  void testSubmitPlanAndNotifyListeners() {
    AtomicInteger notifications = new AtomicInteger(0);

    service.addListener((plan) -> notifications.incrementAndGet());

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

    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
    service.requestDevelopment(reason);
    verify(workflowService).routeToPhase("CODE", reason);

    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
    service.requestReview(reason);
    verify(workflowService).routeToPhase("REVIEW", reason);

    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
    service.requestFixes(reason);
    verify(workflowService, times(2)).routeToPhase("CODE", reason);

    service.requestCompletion(reason);
    verify(workflowService).routeToPhase("PLAN", reason);
  }

  @Test
  void testRequestPlanRevision() {
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
    String reason = "Technical blocker";
    service.requestPlanRevision(reason);
    verify(workflowService, times(1)).routeToPhase("PLAN", reason);
  }

  @Test
  void testPhaseRestrictionsEnforced() {
    // In PLAN phase, markStepCompleted and requestReview should fail
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
    service.submitPlan("T", "G", List.of(), List.of("S1"));

    assertThrows(IllegalStateException.class, () -> service.markStepCompleted(0));
    assertThrows(IllegalStateException.class, () -> service.markStepIncomplete(0));
    assertThrows(IllegalStateException.class, () -> service.requestReview("done"));
    assertThrows(IllegalStateException.class, () -> service.requestFixes("fix"));
    assertThrows(IllegalStateException.class, () -> service.requestCompletion("complete"));
    assertThrows(IllegalStateException.class, () -> service.requestPlanRevision("revise"));

    // In CODE phase, submitPlan, requestDevelopment, requestFixes,
    // requestCompletion should fail
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
    assertDoesNotThrow(() -> service.markStepCompleted(0));
    assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
    assertThrows(
        IllegalStateException.class, () -> service.submitPlan("T2", "G2", List.of(), List.of()));
    assertThrows(IllegalStateException.class, () -> service.requestDevelopment("dev"));
    assertThrows(IllegalStateException.class, () -> service.requestFixes("fix"));
    assertThrows(IllegalStateException.class, () -> service.requestCompletion("complete"));

    // In REVIEW phase, submitPlan, requestDevelopment, requestReview,
    // requestPlanRevision should
    // fail
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
    assertDoesNotThrow(() -> service.markStepCompleted(1));
    assertThrows(
        IllegalStateException.class, () -> service.submitPlan("T3", "G3", List.of(), List.of()));
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
