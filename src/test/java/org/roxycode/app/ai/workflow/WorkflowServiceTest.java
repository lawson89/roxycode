package org.roxycode.app.ai.workflow;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowServiceTest {
    @Test
    void testPhaseManagement() {
        WorkflowService service = new WorkflowService();
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());

        service.setCurrentPhase(WorkflowPhase.DESIGN);
        assertEquals(WorkflowPhase.DESIGN, service.getCurrentPhase());

        service.requestPhaseByName("DEVELOPMENT");
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getPendingPhase());
        service.approveTransition();
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getCurrentPhase());
    }

    @Test
    void testListeners() {
        WorkflowService service = new WorkflowService();
        AtomicReference<WorkflowPhase> observed = new AtomicReference<>();
        
        service.addPhaseListener(observed::set);
        assertEquals(WorkflowPhase.EXPLORE, observed.get());

        service.setCurrentPhase(WorkflowPhase.VERIFICATION);
        assertEquals(WorkflowPhase.VERIFICATION, observed.get());
    }

    @Test
    void testHitlPhaseTransition() {
        WorkflowService service = new WorkflowService();
        AtomicReference<WorkflowPhase> requestedPhase = new AtomicReference<>();
        
        service.addTransitionRequestListener((current, requested) -> requestedPhase.set(requested));
        
        service.requestPhaseTransition(WorkflowPhase.DESIGN);
        assertEquals(WorkflowPhase.DESIGN, service.getPendingPhase());
        assertEquals(WorkflowPhase.DESIGN, requestedPhase.get());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase()); // Should not have changed yet
        
        service.approveTransition();
        assertEquals(WorkflowPhase.DESIGN, service.getCurrentPhase());
        assertNull(service.getPendingPhase());
    }

    @Test
    void testRejectTransition() {
        WorkflowService service = new WorkflowService();
        service.requestPhaseTransition(WorkflowPhase.DEVELOPMENT);
        assertEquals(WorkflowPhase.DEVELOPMENT, service.getPendingPhase());
        
        service.rejectTransition();
        assertNull(service.getPendingPhase());
        assertEquals(WorkflowPhase.EXPLORE, service.getCurrentPhase());
    }
}