package org.roxycode.app.ai.services.specs;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpecTest {
    @Test
    void testFunctionalSpec() {
        FunctionalSpec spec = new FunctionalSpec("Title", "Goal", List.of("Req 1"));
        assertEquals("Title", spec.title());
        assertEquals(1, spec.requirements().size());
    }

    @Test
    void testTechnicalSpec() {
        TechnicalSpec spec = new TechnicalSpec("Goal", List.of("Con 1"), List.of("Step 1"));
        assertEquals("Goal", spec.architectureGoal());
        assertEquals(1, spec.constraints().size());
        assertEquals(1, spec.implementationSteps().size());
    }
}
