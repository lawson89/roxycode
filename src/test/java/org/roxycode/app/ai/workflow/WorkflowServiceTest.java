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

        service.setPhaseByName("DEVELOPMENT");
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
}