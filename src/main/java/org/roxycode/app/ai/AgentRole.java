package org.roxycode.app.ai;

public enum AgentRole {

    PRODUCT_OWNER(
            "Product Owner",
            "Defines the 'what' and 'why' by gathering functional requirements."
    ),
    LEAD_ARCHITECT(
            "Lead Architect",
            "Defines the 'how' by creating technical specifications and implementation plans."
    ),
    SENIOR_DEVELOPER(
            "Senior Developer",
            "Executes the plan by writing clean, tested, and production-grade code."
    ),
    TECHNICAL_MENTOR(
            "Technical Mentor",
            "Provides technical guidance and ensures adherence to best practices."
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
