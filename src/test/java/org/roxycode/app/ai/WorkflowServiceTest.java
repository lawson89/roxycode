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

        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        assertEquals(WorkflowPhase.PLANNING, service.getPendingPhase());
        service.approveTransition();
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
    }

    @Test
    void testListeners() {
        AtomicReference<WorkflowPhase> observed = new AtomicReference<>();
        
        service.addPhaseListener(observed::set);
        assertEquals(WorkflowPhase.EXPLORE, observed.get());

        // Forcing phase change for listener test
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        assertEquals(WorkflowPhase.PLANNING, observed.get());
    }

    @Test
    void testHitlPhaseTransition() {
        AtomicReference<WorkflowPhase> requestedPhase = new AtomicReference<>();
        AtomicReference<String> requestedReason = new AtomicReference<>();
        
        service.addTransitionRequestListener((current, requested, reason) -> {
            requestedPhase.set(requested);
            requestedReason.set(reason);
        });
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Testing transition"));
        assertEquals(WorkflowPhase.PLANNING, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLANNING, requestedPhase.get());
        assertEquals("Testing transition", requestedReason.get());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
        
        service.approveTransition();
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
    }

    @Test
    void testRejectTransition() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        assertEquals(WorkflowPhase.PLANNING, service.getPendingPhase());
        
        service.rejectTransition();
        assertNull(service.getPendingPhase());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testVisitedPhasesTracking() {
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        
        Set<WorkflowPhase> visited = service.getVisitedPhases();
        assertTrue(visited.contains(WorkflowPhase.EXPLORE));
        assertTrue(visited.contains(WorkflowPhase.PLANNING));
        assertFalse(visited.contains(WorkflowPhase.DEVELOPMENT));
    }

    @Test
    void testResetWorkflowHistory() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.PLANNING));
        
        service.resetWorkflow();
        assertEquals(1, service.getVisitedPhases().size());
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
    }

    @Test
    void testExploreToPlanning() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        assertEquals(WorkflowPhase.PLANNING, service.getPendingPhase());
    }

    @Test
    void testPlanningToExploreGated() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("EXPLORE", "Reason"));
        assertEquals(WorkflowPhase.EXPLORE, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
    }

    @Test
    void testExploreToDevelopmentBlocked() {
        String result = service.routeToPhase("DEVELOPMENT", "Reason");
        assertTrue(result.contains("Only single-step transitions to adjacent phases are allowed"));
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testPlanningToDevelopmentGated() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("Title", "Goal", List.of(), List.of()));
        
        YieldTurnException ex = assertThrows(YieldTurnException.class, () -> service.routeToPhase("DEVELOPMENT", "Reason"));
        assertTrue(ex.getMessage().contains("Title"));
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
    }

    @Test
    void testPlanningToDevelopmentMissingPlan() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        
        when(planManager.getCurrentPlan()).thenReturn(null);
        
        String result = service.routeToPhase("DEVELOPMENT", "Reason");
        assertTrue(result.contains("Implementation Plan is missing"));
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
    }

    @Test
    void testDevelopmentToVerificationGated() {
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("Title", "Goal", List.of(), List.of()));
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("DEVELOPMENT", "Reason"));
        service.approveTransition();
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("VERIFICATION", "Reason"));
        assertEquals(WorkflowPhase.VERIFICATION, service.getPendingPhase());
    }

    @Test
    void testIllegalTransitions() {
        // EXPLORE -> VERIFICATION
        assertTrue(service.routeToPhase("VERIFICATION", "Reason").contains("Only single-step transitions to adjacent phases are allowed"));
        
        // PLANNING -> VERIFICATION
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLANNING", "Reason"));
        service.approveTransition();
        assertTrue(service.routeToPhase("VERIFICATION", "Reason").contains("Only single-step transitions to adjacent phases are allowed"));
    }
}
