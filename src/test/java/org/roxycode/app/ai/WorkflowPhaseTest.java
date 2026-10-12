package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {
    @Test
    void testPhaseProperties() {
        assertEquals("Explore", WorkflowPhase.EXPLORE.getDisplayName());
        assertEquals("Plan", WorkflowPhase.PLAN.getDisplayName());
        assertEquals("Code", WorkflowPhase.CODE.getDisplayName());
        
        assertEquals("Technical Mentor", WorkflowPhase.EXPLORE.getPersonaTitle());
        assertEquals("Lead Architect", WorkflowPhase.PLAN.getPersonaTitle());
        assertEquals("Senior Developer", WorkflowPhase.CODE.getPersonaTitle());
        
        assertNotNull(WorkflowPhase.CODE.getDescription());
    }
}
