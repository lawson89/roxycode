package org.roxycode.app.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationPlanTest {
    @Test
    void testImplementationPlan() {
        ImplementationPlan.TechStep step = new ImplementationPlan.TechStep("Step 1", false);
        ImplementationPlan plan = new ImplementationPlan("Title", "Goal", List.of("Req 1"), List.of(step));
        assertEquals("Title", plan.title());
        assertEquals("Goal", plan.goal());
        assertEquals(1, plan.requirements().size());
        assertEquals(1, plan.technicalSteps().size());
        assertFalse(plan.technicalSteps().get(0).completed());
    }
}