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
        
        ImplementationPlan plan = new ImplementationPlan("Title", "Goal", List.of("Req 1"), List.of(new ImplementationPlan.TechStep("Step 1", false)));
        String res = service.submitPlan(plan);
        
        assertEquals("Implementation plan submitted successfully with mandatory verification checklist items.", res);
        assertNotNull(service.getCurrentPlan());
        assertEquals("Title", service.getCurrentPlan().title());
        // Step 1 + Code compiles + Unit tests pass + Code Review = 4
        assertEquals(4, service.getCurrentPlan().technicalSteps().size());
        assertEquals(1, notifications.get());
    }

    @Test
    void testSubmitPlanWithArrays() {
        PlanManagerService service = new PlanManagerService();
        
        String[] reqs = {"Req 1", "Req 2"};
        String[] steps = {"Step 1"};
        service.submitPlan("Title", "Goal", reqs, steps);
        
        assertEquals(2, service.getCurrentPlan().requirements().size());
        // Step 1 + Code compiles + Unit tests pass + Code Review = 4
        assertEquals(4, service.getCurrentPlan().technicalSteps().size());
        assertEquals("Req 1", service.getCurrentPlan().requirements().get(0));
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
    }

    @Test
    void testOverloadedSubmitPlan() {
        PlanManagerService service = new PlanManagerService();
        
        // Simple arguments
        service.submitPlan("Title", "Goal", List.of("Req 1"), List.of("Step 1"));
        assertNotNull(service.getCurrentPlan());
        assertEquals("Title", service.getCurrentPlan().title());
        assertEquals(4, service.getCurrentPlan().technicalSteps().size());
        
        // Map arguments
        service.clearSpecs();
        assertNull(service.getCurrentPlan());
        
        Map<String, Object> planMap = Map.of(
            "title", "Map Title",
            "goal", "Map Goal",
            "requirements", List.of("Req A"),
            "technicalSteps", List.of("Step A")
        );
        service.submitPlan(planMap);
        assertEquals("Map Title", service.getCurrentPlan().title());
        assertEquals(4, service.getCurrentPlan().technicalSteps().size());
    }

    @Test
    void testMarkStepStatus() {
        PlanManagerService service = new PlanManagerService();
        service.submitPlan("Title", "Goal", List.of(), List.of("Step 1", "Step 2"));
        
        // Step 1, Step 2, Code compiles, Unit tests pass, Code Review = 5
        assertEquals(5, service.getCurrentPlan().technicalSteps().size());
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
        
        service.markStepCompleted(0);
        assertTrue(service.getCurrentPlan().technicalSteps().get(0).completed());
        assertFalse(service.getCurrentPlan().technicalSteps().get(1).completed());
        
        service.markStepIncomplete(0);
        assertFalse(service.getCurrentPlan().technicalSteps().get(0).completed());
    }
}