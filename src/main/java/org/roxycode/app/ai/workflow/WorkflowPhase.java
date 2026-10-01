package org.roxycode.app.ai.workflow;

import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.AgentDoc;

@AgentDoc("Defines the current phase of the development workflow.")
public enum WorkflowPhase {
    DISCOVERY(AgentRole.PRODUCT_OWNER),
    DESIGN(AgentRole.LEAD_ARCHITECT),
    DEVELOPMENT(AgentRole.SENIOR_DEVELOPER),
    VERIFICATION(AgentRole.TECHNICAL_MENTOR);

    private final AgentRole role;

    WorkflowPhase(AgentRole role) {
        this.role = role;
    }

    public AgentRole getRole() {
        return role;
    }
}
