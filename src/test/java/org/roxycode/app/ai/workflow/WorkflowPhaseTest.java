package org.roxycode.app.ai.workflow;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.AgentRole;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {
    @Test
    void testPhaseToRoleMapping() {
        assertEquals(AgentRole.PRODUCT_OWNER, WorkflowPhase.DISCOVERY.getRole());
        assertEquals(AgentRole.LEAD_ARCHITECT, WorkflowPhase.DESIGN.getRole());
        assertEquals(AgentRole.SENIOR_DEVELOPER, WorkflowPhase.DEVELOPMENT.getRole());
        assertEquals(AgentRole.TECHNICAL_MENTOR, WorkflowPhase.VERIFICATION.getRole());
    }
}
