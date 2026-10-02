package org.roxycode.app.ai.services.plan;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.specs.FunctionalSpec;
import org.roxycode.app.ai.services.specs.TechnicalSpec;

import java.util.List;
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
}
