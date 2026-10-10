package org.roxycode.app.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.model.ImplementationPlan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private PlanManagerService planManager;
    @Mock
    private EnvironmentService envService;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(planManager, envService);
    }

    @Test
    void testPhaseManagement() {
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());

        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("T", "G", List.of(), List.of(), false, null, null, null));
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("CODE", "Reason"));
        assertEquals(WorkflowPhase.CODE, service.getPendingPhase());
        
        when(envService.getCurrentUser()).thenReturn("testuser");
        service.approveTransition();
        
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());
        verify(planManager).approvePlan(eq("testuser"), anyString());
    }

    @Test
    void testCodeCompletionAndClear() {
        // Setup in CODE phase
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("T", "G", List.of(), List.of(), true, "t1", "u1", null));
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("CODE", "Reason"));
        when(envService.getCurrentUser()).thenReturn("u1");
        service.approveTransition();
        assertEquals(WorkflowPhase.CODE, service.getCurrentPhase());

        // Request transition back to PLAN (Completion)
        ImplementationPlan completedPlan = new ImplementationPlan("T", "G", List.of(), 
            List.of(new ImplementationPlan.TechStep("S1", true)), true, "t1", "u1", null);
        when(planManager.getCurrentPlan()).thenReturn(completedPlan);
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Done"));
        service.approveTransition();
        
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        verify(planManager).completePlan(anyString());
        verify(planManager).clearPlan();
    }

    @Test
    void testBlockedTransitionNoClear() {
        // Setup in CODE phase
        when(planManager.getCurrentPlan()).thenReturn(new ImplementationPlan("T", "G", List.of(), List.of(), true, "t1", "u1", null));
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("CODE", "Reason"));
        when(envService.getCurrentUser()).thenReturn("u1");
        service.approveTransition();

        // Request transition back to PLAN (Blocked)
        ImplementationPlan incompletePlan = new ImplementationPlan("T", "G", List.of(), 
            List.of(new ImplementationPlan.TechStep("S1", false)), true, "t1", "u1", null);
        when(planManager.getCurrentPlan()).thenReturn(incompletePlan);
        
        assertThrows(YieldTurnException.class, () -> service.routeToPhase("PLAN", "Blocked by bug"));
        service.approveTransition();
        
        assertEquals(WorkflowPhase.PLAN, service.getCurrentPhase());
        verify(planManager, never()).completePlan(anyString());
        verify(planManager, never()).clearPlan();
    }
}
