package org.roxycode.app.ai;

import org.kordamp.ikonli.codicons.Codicons;

@AgentDoc("Defines the current phase of the development workflow.")
public enum WorkflowPhase {
    EXPLORE(AgentRole.TECHNICAL_MENTOR, "Explore", Codicons.SEARCH, WorkflowMode.EXPLORE),
    PLAN(AgentRole.LEAD_ARCHITECT, "Plan", Codicons.LIGHTBULB, WorkflowMode.CHANGE),
    CODE(AgentRole.SENIOR_DEVELOPER, "Code", Codicons.CODE, WorkflowMode.CHANGE);

    private final AgentRole role;
    private final String displayName;
    private final Codicons icon;
    private final WorkflowMode mode;

    WorkflowPhase(AgentRole role, String displayName, Codicons icon, WorkflowMode mode) {
        this.role = role;
        this.displayName = displayName;
        this.icon = icon;
        this.mode = mode;
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

    public WorkflowMode getMode() {
        return mode;
    }
}
