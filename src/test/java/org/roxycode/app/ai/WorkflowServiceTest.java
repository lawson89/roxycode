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
import java.util.Collections;
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

        service.setCurrentPhase(WorkflowPhase.PLANNING);
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());

        service.requestPhaseByName("DEVELOPMENT");
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getPendingPhase());
        service.approveTransition();
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getCurrentPhase());
    }

    @Test
    void testListeners() {
        AtomicReference<WorkflowPhase> observed = new AtomicReference<>();
        
        service.addPhaseListener(observed::set);
        assertEquals(WorkflowPhase.EXPLORE, observed.get());

        service.setCurrentPhase(WorkflowPhase.VERIFICATION);
        assertEquals(WorkflowPhase.VERIFICATION, observed.get());
    }

    @Test
    void testHitlPhaseTransition() {
        AtomicReference<WorkflowPhase> requestedPhase = new AtomicReference<>();
        
        service.addTransitionRequestListener((current, requested) -> requestedPhase.set(requested));
        
        service.requestPhaseTransition(WorkflowPhase.PLANNING);
        assertEquals(WorkflowPhase.PLANNING, service.getPendingPhase());
        assertEquals(WorkflowPhase.PLANNING, requestedPhase.get());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase()); // Should not have changed yet
        
        service.approveTransition();
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
    }

    @Test
    void testRejectTransition() {
        service.requestPhaseTransition(WorkflowPhase.DEVELOPMENT);
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getPendingPhase());
        
        service.rejectTransition();
        assertNull(service.getPendingPhase());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testVisitedPhasesTracking() {
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
        
        service.setCurrentPhase(WorkflowPhase.PLANNING);
        
        Set<WorkflowPhase> visited = service.getVisitedPhases();
        assertTrue(visited.contains(WorkflowPhase.EXPLORE));
        assertTrue(visited.contains(WorkflowPhase.PLANNING));
        assertFalse(visited.contains(WorkflowPhase.DEVELOPMENT));
    }

    @Test
    void testResetWorkflowHistory() {
        service.setCurrentPhase(WorkflowPhase.PLANNING);
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.PLANNING));
        
        service.resetWorkflow();
        assertEquals(1, service.getVisitedPhases().size());
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
    }

    @Test
    void testAdvanceToPhaseAutoTransition() {
        String result = service.routeToPhase("PLANNING");
        assertEquals("Advanced to phase: PLANNING", result);
        assertEquals(WorkflowPhase.PLANNING, service.getCurrentPhase());
    }

    @Test
    void testAdvanceToPhaseGatedTransition() {
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("Title", "Goal", List.of(), List.of()));
        String result = service.routeToPhase("DEVELOPMENT");
        assertEquals("Transition to DEVELOPMENT requested. Awaiting human approval.", result);
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getPendingPhase());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testAdvanceToPhaseGuardrailFailure() {
        when(planManager.getCurrentPlan()).thenReturn(null);
        String result = service.routeToPhase("DEVELOPMENT");
        assertTrue(result.contains("Implementation Plan is missing"));
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testResetWorkflow() {
        service.setCurrentPhase(WorkflowPhase.PLANNING);
        service.requestPhaseTransition(WorkflowPhase.DEVELOPMENT);
        
        service.resetWorkflow();
        
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
        assertEquals(1, service.getVisitedPhases().size());
        assertTrue(service.getVisitedPhases().contains(WorkflowPhase.EXPLORE));
        verify(planManager).clearSpecs();
    }

    @Test
    void testRouteToPhaseBackward() {
        service.setCurrentPhase(WorkflowPhase.PLANNING);
        
        String result = service.routeToPhase("EXPLORE");
        
        assertEquals("Routed back to phase: EXPLORE", result);
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }

    @Test
    void testSetCurrentPhaseNullCheck() {
        WorkflowPhase initial = service.getCurrentPhase();
        service.setCurrentPhase(null);
        assertEquals(initial, service.getCurrentPhase(), "Phase should not change when null is passed");
    }
}