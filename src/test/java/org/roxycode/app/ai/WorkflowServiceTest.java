package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

  @Mock private PlanService planManager;
  @Mock private EnvironmentService envService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private WorkflowService service;

  @BeforeEach
  void setUp() {
    service = new WorkflowService(planManager, envService, eventPublisher);
  }

  private void setupCodePhase() {
    ImplementationPlan plan =
        new ImplementationPlan(
            "T", "G", List.of(), List.of(), true, "now", "user", null, List.of());
    lenient().when(planManager.getCurrentPlan()).thenReturn(plan);
    try {
      service.routeToPhase("CODE", "Reason");
    } catch (YieldTurnException e) {
    }
    lenient().when(envService.getCurrentUser()).thenReturn("user");
    service.approveTransition();
    assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    reset(planManager); // Clear interactions from setup
    lenient().when(planManager.getCurrentPlan()).thenReturn(plan);
  }

  @Test
  void testPhaseManagement() {
    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

    when(planManager.getCurrentPlan())
        .thenReturn(
            new ImplementationPlan(
                "T", "G", List.of(), List.of(), false, null, null, null, List.of()));

    assertThrows(YieldTurnException.class, () -> service.routeToPhase("CODE", "Reason"));
    assertEquals(WorkflowPhase.CODE, service.getPendingPhase());

    when(envService.getCurrentUser()).thenReturn("testuser");
    service.approveTransition();

    assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    assertEquals(null, service.getPendingPhase());
  }

  @Test
  void testCodeCompletionAndClear() {
    setupCodePhase();

    // Request PLAN (completion) - Autonomous transition yields turn
    YieldTurnException ex =
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Done"));
    assertTrue(ex.getMessage().contains("Autonomous transition"));

    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    assertNull(service.getPendingPhase());
  }

  @Test
  void testBlockedTransitionNoClear() {
    setupCodePhase();

    // Request PLAN - Autonomous transition yields turn
    YieldTurnException ex =
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Moving on"));
    assertTrue(ex.getMessage().contains("Autonomous transition"));
    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    assertNull(service.getPendingPhase());
  }

  @Test
  void testAutonomousReviewTransition() {
    setupCodePhase();

    // Request REVIEW - Autonomous transition yields turn
    YieldTurnException ex =
        assertThrows(
            YieldTurnException.class, () -> service.routeToPhase("REVIEW", "Checking quality"));
    assertTrue(
        ex.getMessage().contains("Autonomous transition"),
        "Expected autonomous yield message but got: " + ex.getMessage());
    assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

    verify(eventPublisher).publishEvent(any(AutonomousTransitionEvent.class));
    verify(planManager).addHandoffLogEntry(anyString(), anyString(), anyString());
  }

  @Test
  void testExploreDecoupling() {
    setupCodePhase();
    // Transition to EXPLORE should fail now because it's decoupled
    String result = service.routeToPhase("EXPLORE", "Jump");
    assertTrue(result.contains("Error: Cannot jump from CODE to EXPLORE"));

    service.resetWorkflow();
    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
  }

  @Test
  void testReviewCompletionAndClear() {
    setupCodePhase();
    assertThrows(
        YieldTurnException.class,
        () -> service.routeToPhase("REVIEW", "Checking quality")); // Autonomous
    assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

    ImplementationPlan.TechStep step = new ImplementationPlan.TechStep("Desc", true);
    ImplementationPlan plan =
        new ImplementationPlan(
            "Title", "Goal", List.of(), List.of(step), true, "now", "user", null, List.of());
    when(planManager.getCurrentPlan()).thenReturn(plan);

    // Request PLAN from REVIEW - Requires HITL Approval
    assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Finalizing"));
    assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());

    when(envService.getCurrentUser()).thenReturn("testuser");
    service.approveTransition();

    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    verify(planManager).completePlan(anyString());
    verify(planManager).clearPlan();
  }
}
