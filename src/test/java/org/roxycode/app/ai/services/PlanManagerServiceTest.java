package org.roxycode.app.ai.services;

import org.junit.jupiter.api.Test;
import org.roxycode.app.model.FunctionalSpec;
import org.roxycode.app.model.TechnicalSpec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlanManagerServiceTest {

    @Test
    void testSubmitSpecsAndNotifyListeners() {
        PlanManagerService service = new PlanManagerService();
        AtomicInteger notifications = new AtomicInteger(0);
        
        service.addListener((fs, ts) -> notifications.incrementAndGet());
        
        FunctionalSpec fs = new FunctionalSpec("Title", "Goal", List.of("Req 1"));
        String res1 = service.submitFunctionalSpec(fs);
        
        assertEquals("Functional spec submitted successfully.", res1);
        assertEquals(fs, service.getCurrentFunctionalSpec());
        assertEquals(1, notifications.get());
        
        TechnicalSpec ts = new TechnicalSpec("Arch", List.of("Con 1"), List.of("Step 1"));
        String res2 = service.submitTechnicalSpec(ts);
        
        assertEquals("Technical spec submitted successfully.", res2);
        assertEquals(ts, service.getCurrentTechnicalSpec());
        assertEquals(2, notifications.get());
    }

    @Test
    void testSubmitSpecsWithArrays() {
        PlanManagerService service = new PlanManagerService();
        
        // Passing String array instead of List
        String[] reqs = {"Req 1", "Req 2"};
        service.submitFunctionalSpec("Title", "Goal", reqs);
        
        assertEquals(2, service.getCurrentFunctionalSpec().requirements().size());
        assertEquals("Req 1", service.getCurrentFunctionalSpec().requirements().get(0));

        // Technical spec with arrays
        String[] cons = {"Con 1"};
        String[] steps = {"Step 1"};
        service.submitTechnicalSpec("Arch", cons, steps);
        
        assertEquals(1, service.getCurrentTechnicalSpec().constraints().size());
        assertEquals(1, service.getCurrentTechnicalSpec().implementationSteps().size());
    }

    @Test
    void testOverloadedSubmitSpecs() {
        PlanManagerService service = new PlanManagerService();
        
        // Simple arguments
        service.submitFunctionalSpec("Title", "Goal", List.of("Req 1"));
        assertNotNull(service.getCurrentFunctionalSpec());
        assertEquals("Title", service.getCurrentFunctionalSpec().title());
        
        service.submitTechnicalSpec("Arch", List.of("Con 1"), List.of("Step 1"));
        assertNotNull(service.getCurrentTechnicalSpec());
        assertEquals("Arch", service.getCurrentTechnicalSpec().architectureGoal());
        
        // Map arguments
        service.clearSpecs();
        assertNull(service.getCurrentFunctionalSpec());
        
        Map<String, Object> fsMap = Map.of(
            "title", "Map Title",
            "goal", "Map Goal",
            "requirements", List.of("Req A")
        );
        service.submitFunctionalSpec(fsMap);
        assertEquals("Map Title", service.getCurrentFunctionalSpec().title());
        
        Map<String, Object> tsMap = Map.of(
            "architectureGoal", "Map Arch",
            "constraints", List.of("Con A"),
            "implementationSteps", List.of("Step A")
        );
        service.submitTechnicalSpec(tsMap);
        assertEquals("Map Arch", service.getCurrentTechnicalSpec().architectureGoal());
    }
}