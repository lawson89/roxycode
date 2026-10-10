package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.AgentRole;
import org.kordamp.ikonli.codicons.Codicons;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {
    @Test
    void testPhaseProperties() {
        assertEquals(AgentRole.LEAD_ARCHITECT, WorkflowPhase.PLAN.getRole());
        assertEquals("Plan", WorkflowPhase.PLAN.getDisplayName());
        assertEquals(Codicons.LIGHTBULB, WorkflowPhase.PLAN.getIcon());
        assertEquals(WorkflowMode.CHANGE, WorkflowPhase.PLAN.getMode());

        assertEquals(AgentRole.SENIOR_DEVELOPER, WorkflowPhase.CODE.getRole());
        assertEquals("Code", WorkflowPhase.CODE.getDisplayName());
        assertEquals(Codicons.CODE, WorkflowPhase.CODE.getIcon());
        assertEquals(WorkflowMode.CHANGE, WorkflowPhase.CODE.getMode());

        assertEquals(AgentRole.TECHNICAL_MENTOR, WorkflowPhase.EXPLORE.getRole());
        assertEquals("Explore", WorkflowPhase.EXPLORE.getDisplayName());
        assertEquals(Codicons.SEARCH, WorkflowPhase.EXPLORE.getIcon());
        assertEquals(WorkflowMode.EXPLORE, WorkflowPhase.EXPLORE.getMode());
    }
}