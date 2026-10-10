package org.roxycode.app.ai.services;

import org.junit.jupiter.api.Test;
import org.roxycode.app.model.ImplementationPlan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlanManagerServiceTest {

    @Test
    void testSubmitPlanAndNotifyListeners() {
        PlanManagerService service = new PlanManagerService();
        AtomicInteger notifications = new AtomicInteger(0);
        
        service.addListener((plan) -> notifications.incrementAndGet());
        
        ImplementationPlan plan = new ImplementationPlan("Title", "Goal", List.of("Req 1"), 
            List.of(new ImplementationPlan.TechStep("Step 1", false)), false, null, null, null);
        String res = service.submitPlan(plan);
        
        assertEquals("Implementation plan submitted successfully with mandatory verification checklist items.", res);
        assertNotNull(service.getCurrentPlan());
        assertEquals("Title", service.getCurrentPlan().title());
        assertEquals(4, service.getCurrentPlan().technicalSteps().size());
        assertEquals(1, notifications.get());
    }

    @Test
    void testApproveAndCompletePlan() {
        PlanManagerService service = new PlanManagerService();
        service.submitPlan("Title", "Goal", List.of(), List.of("Step 1"));
        
        assertFalse(service.getCurrentPlan().userApproved());
        
        service.approvePlan("user1", "2023-01-01");
        assertTrue(service.getCurrentPlan().userApproved());
        assertEquals("user1", service.getCurrentPlan().approvedBy());
        
        service.completePlan("2023-01-02");
        assertEquals("2023-01-02", service.getCurrentPlan().completedOn());
    }

    @Test
    void testClearPlan() {
        PlanManagerService service = new PlanManagerService();
        service.submitPlan("T", "G", List.of(), List.of("S"));
        assertNotNull(service.getCurrentPlan());
        
        service.clearPlan();
        assertNull(service.getCurrentPlan());
    }
}
