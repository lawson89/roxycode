package org.roxycode.app.ai;

public enum WorkflowPhase {
    EXPLORE("Explore", "Technical Mentor", "Your focus is codebase discovery and context gathering."),
    PLAN("Plan", "Lead Architect", "Your focus is architectural specification and technical planning."),
    CODE("Code", "Senior Developer", "Your focus is high-quality implementation and testing."),
    REVIEW("Review", "Code Reviewer", "Your focus is auditing code quality, adherence to plan, and system integrity.");

    private final String displayName;
    private final String personaTitle;
    private final String description;

    WorkflowPhase(String displayName, String personaTitle, String description) {
        this.displayName = displayName;
        this.personaTitle = personaTitle;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPersonaTitle() {
        return personaTitle;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isAllowed(String[] allowedPhases, WorkflowPhase currentPhase) {
        if (allowedPhases == null || allowedPhases.length == 0) {
            return false;
        }
        if (currentPhase == null) {
            return true;
        }
        for (String token : allowedPhases) {
            if ("*".equals(token) || currentPhase.name().equals(token)) {
                return true;
            }
        }
        return false;
    }

    public static WorkflowPhase fromObject(Object raw, WorkflowPhase fallback) {
        if (raw instanceof WorkflowPhase wp) {
            return wp;
        }
        if (raw instanceof String s) {
            try {
                return WorkflowPhase.valueOf(s);
            } catch (IllegalArgumentException ignored) {
                // fall through to fallback
            }
        }
        return fallback;
    }
}
