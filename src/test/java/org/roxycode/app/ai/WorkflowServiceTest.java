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
            "T", "G", List.of(), List.of(), true, "now", "user", null, List.of(), List.of());
    lenient().when(planManager.getCurrentPlan()).thenReturn(plan);
    service.switchMode(WorkflowPhase.PLAN);
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
    service.switchMode(WorkflowPhase.PLAN);
    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

    when(planManager.getCurrentPlan())
        .thenReturn(
            new ImplementationPlan(
                "T", "G", List.of(), List.of(), false, null, null, null, List.of(), List.of()));

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

    // Request PLAN (completion) - This now requires approval
    assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Done"));

    assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
  }

  @Test
  void testBlockedTransitionNoClear() {
    setupCodePhase();

    // Request PLAN - Now requires approval
    assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Moving on"));
    assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
    assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
  }

  @Test
  void testAutonomousReviewTransition() {
    setupCodePhase();

    // Request REVIEW
    String result = service.routeToPhase("REVIEW", "Checking quality");
    assertTrue(result.contains("Autonomous transition"), "Expected autonomous but got: " + result);
    assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

    verify(eventPublisher).publishEvent(any(AutonomousTransitionEvent.class));
        verify(planManager).addHandoffLogEntry(anyString(), anyString(), anyString());
  }

  @Test
  void testExploreDecoupling() {
    setupCodePhase();
    // Any phase to EXPLORE should now yield for approval because it's adjacent but not autonomous
    assertThrows(YieldTurnException.class, () -> service.routeToPhase("EXPLORE", "Jump"));

    service.resetWorkflow();
    assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    // Already in EXPLORE, so it shouldn't throw YieldTurnException if we call routeToPhase for same phase?
    // Actually routeToPhase returns a message if already in phase.
    assertEquals("Already in phase: EXPLORE", service.routeToPhase("EXPLORE", "Let's discover"));
  }

  @Test
  void testReviewCompletionAndClear() {
    setupCodePhase();
    service.routeToPhase("REVIEW", "Checking quality"); // Autonomous
    assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());

    ImplementationPlan plan = mock(ImplementationPlan.class);
    when(plan.title()).thenReturn("Title");
    when(plan.goal()).thenReturn("Goal");
    when(plan.requirements()).thenReturn(List.of());
    ImplementationPlan.TechStep step = new ImplementationPlan.TechStep("Desc", true);
    when(plan.technicalSteps()).thenReturn(List.of(step));
    when(planManager.getCurrentPlan()).thenReturn(plan);

    // Request PLAN from REVIEW
    assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Finalizing"));

    when(envService.getCurrentUser()).thenReturn("user");
    service.approveTransition();

    assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    verify(planManager).completePlan(anyString());
    verify(planManager).clearPlan();
  }
}
