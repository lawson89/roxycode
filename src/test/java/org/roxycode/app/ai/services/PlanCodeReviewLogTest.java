package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;

class PlanCodeReviewLogTest {

  @Test
  void testCodeReviewLogPersistence() {
    WorkflowService workflowService = mock(WorkflowService.class);
    PlanService planService = new PlanService(workflowService);

    ImplementationPlan plan =
        new ImplementationPlan(
            "Title", "Goal", List.of(), List.of(), false, null, null, null, List.of(), List.of());
    planService.submitPlan(plan);

    planService.addCodeReviewLogEntry("Review entry 1");

    ImplementationPlan updatedPlan = planService.getCurrentPlan();
    assertEquals(1, updatedPlan.codeReviewLog().size());
    assertEquals("Review entry 1", updatedPlan.codeReviewLog().get(0).message());
  }

  @Test
  void testWorkflowServiceLogsCodeReviewOnTransition() {
    EnvironmentService envService = mock(EnvironmentService.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    // We need a real PlanService to test the interaction
    PlanService planService = spy(new PlanService(null));
    WorkflowService workflowService = new WorkflowService(planService, envService, eventPublisher);

    // Inject workflowService into planService
    java.lang.reflect.Field field;
    try {
      field = PlanService.class.getDeclaredField("workflowService");
      field.setAccessible(true);
      field.set(planService, workflowService);
    } catch (Exception e) {
      fail(e);
    }

    ImplementationPlan plan =
        new ImplementationPlan(
            "Title", "Goal", List.of(), List.of(), true, "now", "user", null, List.of(), List.of());
    planService.submitPlan(plan);

    // Target phase: CODE (current is PLAN, so this should work)

    // Manually set phase to CODE for testing
    try {
      java.lang.reflect.Field phaseField = WorkflowService.class.getDeclaredField("currentPhase");
      phaseField.setAccessible(true);
      phaseField.set(workflowService, WorkflowPhase.CODE);
    } catch (Exception e) {
      fail(e);
    }

    String result = workflowService.routeToPhase("REVIEW", "Ready for review");

    ImplementationPlan reviewPlan = planService.getCurrentPlan();
    assertNotNull(reviewPlan);
    assertFalse(reviewPlan.codeReviewLog().isEmpty());
    assertTrue(reviewPlan.codeReviewLog().get(0).message().contains("CODE -> REVIEW"));
    assertTrue(reviewPlan.codeReviewLog().get(0).message().contains("Ready for review"));
  }
}
