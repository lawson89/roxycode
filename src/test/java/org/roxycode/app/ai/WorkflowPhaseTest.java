package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.AgentRole;
import org.kordamp.ikonli.codicons.Codicons;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {
    @Test
    void testPhaseProperties() {
        assertEquals(AgentRole.PRODUCT_OWNER, WorkflowPhase.DISCOVERY.getRole());
        assertEquals("Discovery", WorkflowPhase.DISCOVERY.getDisplayName());
        assertEquals(Codicons.TELESCOPE, WorkflowPhase.DISCOVERY.getIcon());

        assertEquals(AgentRole.SENIOR_DEVELOPER, WorkflowPhase.DEVELOPMENT.getRole());
        assertEquals("Development", WorkflowPhase.DEVELOPMENT.getDisplayName());
        assertEquals(Codicons.CODE, WorkflowPhase.DEVELOPMENT.getIcon());
    }
}