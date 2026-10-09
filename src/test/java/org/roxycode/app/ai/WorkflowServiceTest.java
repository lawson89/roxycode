package org.roxycode.app.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.ImplementationPlan;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private PlanManagerService planManager;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(planManager);
    }

    @Test
    void testPhaseManagement() {
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());

        service.approveTransition(); // No pending phase, nothing happens
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());

        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

    @Test
    void testListeners() {
        AtomicReference<WorkflowPhase> observed = new AtomicReference<>();
        
        service.addPhaseListener(observed::set);
        assertEquals(WorkflowPhase.EXPLORE, observed.get());

        // Forcing phase change for listener test
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, observed.get());
    }

    @Test
    void testHitlPhaseTransition() {
        AtomicReference<WorkflowPhase> requestedPhase = new AtomicReference<>();
        AtomicReference<String> requestedReason = new AtomicReference<>();
        
        service.addTransitionRequestListener((current, requested, reason) -> {
            requestedPhase.set(requested);
            requestedReason.set(reason);
        });
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Testing transition"));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLAN, requestedPhase.get());
        assertEquals("Testing transition", requestedReason.get());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
        
        service.approveTransition();
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
    }

    @Test
    void testRejectTransition() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
        
        service.rejectTransition();
        assertNull(service.getPendingPhase());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testVisitedPhasesTracking() {
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        
        Set<WorkflowPhase> visited = service.getVisitedPhases();
        assertTrue(visited.contains(WorkflowPhase.EXPLORE));
        assertTrue(visited.contains(WorkflowPhase.PLAN));
        assertFalse(visited.contains(WorkflowPhase.DEVELOP));
    }

    @Test
    void testResetWorkflowHistory() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.PLAN));
        
        service.resetWorkflow();
        assertEquals(1, service.getVisitedPhases().size());
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
    }

    @Test
    void testExploreToPlan() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        assertEquals(WorkflowPhase.PLAN, service.getPendingPhase());
    }

    @Test
    void testPlanToExploreGated() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("EXPLORE", "Reason"));
        assertEquals(WorkflowPhase.EXPLORE, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

    @Test
    void testExploreToDevelopBlocked() {
        String result = service.routeToPhase("DEVELOP", "Reason");
        assertTrue(result.contains("Only single-step transitions to adjacent phases are allowed"));
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testPlanToDevelopGated() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("Title", "Goal", List.of(), List.of()));
        
        YieldTurnException ex = assertThrows(YieldTurnException.class, () -> service.routeToPhase("DEVELOP", "Reason"));
        assertTrue(ex.getMessage().contains("Title"));
        assertEquals(WorkflowPhase.DEVELOP, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

    @Test
    void testPlanToDevelopMissingPlan() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        
        when(planManager.getCurrentPlan()).thenReturn(null);
        
        String result = service.routeToPhase("DEVELOP", "Reason");
        assertTrue(result.contains("Implementation Plan is missing"));
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
    }

        @Test
    void testDevelopToExploreBlockedByIncompleteSteps() {
        // Move to DEVELOP
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        ImplementationPlan plan = new ImplementationPlan("Title", "Goal", List.of(), 
            List.of(new ImplementationPlan.TechStep("Step 1", false)));
        when(planManager.getCurrentPlan()).thenReturn(plan);
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("DEVELOP", "Reason"));
        service.approveTransition();
        
        // Attempt to complete DEVELOP
        String result = service.routeToPhase("EXPLORE", "Finished");
        assertTrue(result.contains("Some technical steps are not yet marked as completed"));
        assertEquals(WorkflowPhase.DEVELOP, service.getCurrentPhase());
    }

    @Test
    void testDevelopToExploreAllowedWhenStepsComplete() {
        // Move to DEVELOP
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        ImplementationPlan plan = new ImplementationPlan("Title", "Goal", List.of(), 
            List.of(new ImplementationPlan.TechStep("Step 1", true)));
        when(planManager.getCurrentPlan()).thenReturn(plan);
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("DEVELOP", "Reason"));
        service.approveTransition();
        
        // Attempt to complete DEVELOP
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("EXPLORE", "Finished"));
        assertEquals(WorkflowPhase.EXPLORE, service.getPendingPhase());
    }


    @Test
    void testIllegalTransitions() {
        // EXPLORE -> DEVELOP
        assertTrue(service.routeToPhase("DEVELOP", "Reason").contains("Only single-step transitions to adjacent phases are allowed"));
        
        // DEVELOP -> EXPLORE (via routeToPhase)
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Reason"));
        service.approveTransition();
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("Title", "Goal", List.of(), List.of()));
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("DEVELOP", "Reason"));
        service.approveTransition();

        assertThrows(YieldTurnException.class, () -> service.routeToPhase("EXPLORE", "Reason"));
        assertEquals(WorkflowPhase.EXPLORE, service.getPendingPhase());
    }
}
