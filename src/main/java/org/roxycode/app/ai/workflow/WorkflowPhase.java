package org.roxycode.app.ai.workflow;

import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.AgentDoc;
import org.kordamp.ikonli.codicons.Codicons;

@AgentDoc("Defines the current phase of the development workflow.")
public enum WorkflowPhase {
    EXPLORE(AgentRole.TECHNICAL_MENTOR, "Explore", Codicons.SEARCH),
    DISCOVERY(AgentRole.PRODUCT_OWNER, "Discovery", Codicons.TELESCOPE),
    DESIGN(AgentRole.LEAD_ARCHITECT, "Design", Codicons.SYMBOL_RULER),
    DEVELOPMENT(AgentRole.SENIOR_DEVELOPER, "Development", Codicons.CODE),
    VERIFICATION(AgentRole.TECHNICAL_MENTOR, "Verification", Codicons.BEAKER);

    private final AgentRole role;
    private final String displayName;
    private final Codicons icon;

    WorkflowPhase(AgentRole role, String displayName, Codicons icon) {
        this.role = role;
        this.displayName = displayName;
        this.icon = icon;
    }

    public AgentRole getRole() {
        return role;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Codicons getIcon() {
        return icon;
    }
}