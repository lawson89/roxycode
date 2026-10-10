package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {
    @Test
    void testPhaseProperties() {
        assertEquals(AgentRole.LEAD_ARCHITECT, WorkflowPhase.PLAN.getRole());
        assertEquals(AgentRole.SENIOR_DEVELOPER, WorkflowPhase.CODE.getRole());
        assertEquals(AgentRole.TECHNICAL_MENTOR, WorkflowPhase.EXPLORE.getRole());
    }
}
