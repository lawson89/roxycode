package org.roxycode.app.ai;

import org.kordamp.ikonli.codicons.Codicons;

@AgentDoc("Defines the current phase of the development workflow.")
public enum WorkflowPhase {
    EXPLORE(AgentRole.TECHNICAL_MENTOR, "Explore", Codicons.SEARCH),
    PLAN(AgentRole.LEAD_ARCHITECT, "Plan", Codicons.LIGHTBULB),
    DEVELOP(AgentRole.SENIOR_DEVELOPER, "Develop", Codicons.CODE);

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