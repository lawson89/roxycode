package org.roxycode.app.ai;

import org.kordamp.ikonli.codicons.Codicons;

public enum WorkflowPhase {
    EXPLORE(
            "Explore",
            "Technical Mentor",
            Codicons.SEARCH,
            "Your focus is codebase discovery and context gathering."),
    PLAN(
            "Plan",
            "Lead Architect",
            Codicons.LIST_ORDERED,
            "Your focus is architectural specification and technical planning."),
    CODE(
            "Code",
            "Senior Developer",
            Codicons.CODE,
            "Your focus is high-quality implementation and testing."),
    REVIEW(
            "Review",
            "Code Reviewer",
            Codicons.CHECK_ALL,
            "Your focus is auditing code quality, adherence to plan, and system integrity.");

    private final String displayName;
    private final String personaTitle;
    private final Codicons icon;
    private final String description;

    WorkflowPhase(String displayName, String personaTitle, Codicons icon, String description) {
        this.displayName = displayName;
        this.personaTitle = personaTitle;
        this.icon = icon;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPersonaTitle() {
        return personaTitle;
    }

    public Codicons getIcon() {
        return icon;
    }

    public String getDescription() {
        return description;
    }
}
