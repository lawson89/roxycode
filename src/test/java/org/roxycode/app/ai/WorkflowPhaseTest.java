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

        assertEquals(AgentRole.SENIOR_DEVELOPER, WorkflowPhase.DEVELOP.getRole());
        assertEquals("Develop", WorkflowPhase.DEVELOP.getDisplayName());
        assertEquals(Codicons.CODE, WorkflowPhase.DEVELOP.getIcon());
    }
}