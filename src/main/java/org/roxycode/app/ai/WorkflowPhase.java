package org.roxycode.app.ai;

import org.kordamp.ikonli.codicons.Codicons;

@AgentDoc("Defines the current phase of the development workflow.")
public enum WorkflowPhase {
    EXPLORE("Explore", Codicons.SEARCH, AgentRole.TECHNICAL_MENTOR),
    PLAN("Plan", Codicons.LIST_ORDERED, AgentRole.LEAD_ARCHITECT),
    CODE("Code", Codicons.CODE, AgentRole.SENIOR_DEVELOPER);

    private final String displayName;
    private final Codicons icon;
    private final AgentRole role;

    WorkflowPhase(String displayName, Codicons icon, AgentRole role) {
        this.displayName = displayName;
        this.icon = icon;
        this.role = role;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Codicons getIcon() {
        return icon;
    }

    public AgentRole getRole() {
        return role;
    }
}
