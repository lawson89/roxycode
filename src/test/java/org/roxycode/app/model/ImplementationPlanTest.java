package org.roxycode.app.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationPlanTest {
    @Test
    void testImplementationPlan() {
        ImplementationPlan plan = new ImplementationPlan("Title", "Goal", List.of("Req 1"), List.of("Step 1"));
        assertEquals("Title", plan.title());
        assertEquals("Goal", plan.goal());
        assertEquals(1, plan.requirements().size());
        assertEquals(1, plan.technicalSteps().size());
    }
}