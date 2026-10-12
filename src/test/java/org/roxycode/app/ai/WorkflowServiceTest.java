package org.roxycode.app.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;
import org.roxycode.app.events.AutonomousTransitionEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private PlanManagerService planManager;
    @Mock
    private EnvironmentService envService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(planManager, envService, eventPublisher);
    }

    private void setupCodePhase() {
        ImplementationPlan plan = new ImplementationPlan("T", "G", List.of(), List.of(), true, "now", "user", null, null, List.of());
        lenient().when(planManager.getCurrentPlan()).thenReturn(plan);
        service.switchMode(WorkflowPhase.PLAN);
        try { service.routeToPhase("CODE", "Reason"); } catch (YieldTurnException e) {}
        lenient().when(envService.getCurrentUser()).thenReturn("user");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        reset(planManager); // Clear interactions from setup
        lenient().when(planManager.getCurrentPlan()).thenReturn(plan);
    }

    @Test
    void testPhaseManagement() {
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("T", "G", List.of(), List.of(), false, null, null, null, null, List.of()));
        
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
        
        // Request PLAN (completion) - This is now autonomous!
        String result = service.routeToPhase("PLAN", "Done");
        assertTrue(result.contains("Autonomous transition"), "Expected autonomous but got: " + result);
        
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        verify(planManager).addHandoffLogEntry(anyString());
        verify(eventPublisher).publishEvent(any(AutonomousTransitionEvent.class));
    }

    @Test
    void testBlockedTransitionNoClear() {
        setupCodePhase();
        
        // Request PLAN - This is autonomous, so it proceeds regardless of incomplete steps in the new logic
        String result = service.routeToPhase("PLAN", "Moving on");
        assertTrue(result.contains("Autonomous transition"), "Expected autonomous but got: " + result);
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

    @Test
    void testAutonomousReviewTransition() {
        setupCodePhase();

        // Request REVIEW
        String result = service.routeToPhase("REVIEW", "Checking quality");
        assertTrue(result.contains("Autonomous transition"), "Expected autonomous but got: " + result);
        assertEquals(WorkflowPhase.REVIEW, service.getCurrentPhase());
        
        verify(eventPublisher).publishEvent(any(AutonomousTransitionEvent.class));
        verify(planManager).addHandoffLogEntry(anyString());
    }
}
