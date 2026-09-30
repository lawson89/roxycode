package org.roxycode.app.ai;

public enum AgentRole {

    ARCHITECT(
            "Lead Software Architect",
            "Analyzes requirements and creates step-by-step implementation blueprints. Does not write production code."
    ),
    CODER(
            "Senior Implementation Engineer",
            "Executes architectural blueprints by writing, modifying, and integrating production code."
    ),
    REVIEWER(
            "Quality Assurance Lead",
            "Verifies implementation against blueprints and runs tests. Reports strict approvals or failures."
    );

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

    /**
     * Helper method to generate the standard system prompt prefix for each
     * role.
     */
    public String getSystemPromptPrefix() {
        return String.format("You are the %s. %s", this.title, this.description);
    }
}
