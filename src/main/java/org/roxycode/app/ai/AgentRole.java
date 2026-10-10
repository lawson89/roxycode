package org.roxycode.app.ai;

public enum AgentRole {
    TECHNICAL_MENTOR("Technical Mentor", "Expert guide for codebase exploration and conceptual understanding."),
    LEAD_ARCHITECT("Lead Architect", "Focuses on requirements, system design, and implementation planning."),
    SENIOR_DEVELOPER("Senior Developer", "Responsible for writing high-quality, tested code according to the plan.");

    private final String title;
    private final String description;

    AgentRole(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
