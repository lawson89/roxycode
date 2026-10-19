package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkflowPhaseTest {
    @Test
    void testPhaseProperties() {
        assertEquals("Explore", WorkflowPhase.EXPLORE.getDisplayName());
        assertEquals("Plan", WorkflowPhase.PLAN.getDisplayName());
        assertEquals("Code", WorkflowPhase.CODE.getDisplayName());
        assertEquals("Review", WorkflowPhase.REVIEW.getDisplayName());

        assertEquals("Technical Mentor", WorkflowPhase.EXPLORE.getPersonaTitle());
        assertEquals("Lead Architect", WorkflowPhase.PLAN.getPersonaTitle());
        assertEquals("Senior Developer", WorkflowPhase.CODE.getPersonaTitle());
        assertEquals("Code Reviewer", WorkflowPhase.REVIEW.getPersonaTitle());

        assertNotNull(WorkflowPhase.CODE.getDescription());
        assertNotNull(WorkflowPhase.REVIEW.getDescription());
    }
}
