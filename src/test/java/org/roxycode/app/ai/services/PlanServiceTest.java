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
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.model.ImplementationPlan;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

  @Mock private WorkflowService workflowService;

  private PlanService service;

  @BeforeEach
  void setUp() {
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

    service.requestDevelopment(reason);
    verify(workflowService).routeToPhase("CODE", reason);

    service.requestReview(reason);
    verify(workflowService).routeToPhase("REVIEW", reason);

    service.requestFixes(reason);
    verify(workflowService, times(2)).routeToPhase("CODE", reason);

    service.requestCompletion(reason);
    verify(workflowService).routeToPhase("PLAN", reason);
  }

  @Test
  void testRequestPlanRevision() {
    String reason = "Technical blocker";
    service.requestPlanRevision(reason);
    verify(workflowService, times(1)).routeToPhase("PLAN", reason);
  }
}
