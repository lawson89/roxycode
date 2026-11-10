package org.roxycode.app.ai;

/**
 * Defines the distinct phases of the RoxyCode development workflow, each with specific personas, goals, and tool access
 * permissions.
 */
public enum WorkflowPhase {
    EXPLORE("Explore", "Technical Mentor", "Your focus is codebase discovery and context gathering.",
            """
                    <html><div style='width: 310px;'>
                    <b>Explore Phase &mdash; Technical Mentor</b><br><br>
                    &bull; <b>What Roxy does:</b> Inspects files, symbols, and git history in strictly <b>read-only</b> mode to answer questions and explain architecture.<br>
                    &bull; <b>Transitions:</b> Send findings to Feature Development via <b>[Move to Feature Request]</b>, or ask Roxy any question about how to use or configure RoxyCode!
                    </div></html>"""),
    PLAN("Plan", "Lead Architect", "Your focus is architectural specification and technical planning.",
            """
                    <html><div style='width: 310px;'>
                    <b>1. Plan Phase &mdash; Lead Architect</b><br><br>
                    &bull; <b>What Roxy does:</b> Explores the repository (read-only) and drafts a structured implementation plan with requirements, technical steps, and verification gates.<br>
                    &bull; <b>Transition (Human-in-the-Loop):</b> Roxy requests transition to <b>Code</b> and waits for you to click <b>Approve</b> (or <b>Reject</b> to request changes).
                    </div></html>"""),
    CODE("Code", "Senior Developer", "Your focus is high-quality implementation and testing.",
            """
                    <html><div style='width: 310px;'>
                    <b>2. Code Phase &mdash; Senior Developer</b><br><br>
                    &bull; <b>What Roxy does:</b> Executes the approved plan by editing files, marking technical steps complete, and running <code>build.toml</code> compile, test, and lint gates.<br>
                    &bull; <b>Transition (Autonomous):</b> Once all steps, compilation, and unit tests pass, Roxy automatically transitions to <b>Review</b> (or requests a plan revision if blocked).
                    </div></html>"""),
    REVIEW("Review", "Code Reviewer", "Your focus is auditing code quality, adherence to plan, and system integrity.",
            """
                    <html><div style='width: 310px;'>
                    <b>3. Review Phase &mdash; Code Reviewer</b><br><br>
                    &bull; <b>What Roxy does:</b> Audits the <code>git diff</code>, test coverage, and code quality against the plan.<br>
                    &bull; <b>Transitions:</b> Autonomously routes back to <b>Code</b> if defects are found, or requests final <b>Approve / Reject</b> sign-off from you to archive the plan and finish.
                    </div></html>""");

    private final String displayName;
    private final String personaTitle;
    private final String description;
    private final String tooltipHtml;

    WorkflowPhase(String displayName, String personaTitle, String description, String tooltipHtml) {
        this.displayName = displayName;
        this.personaTitle = personaTitle;
        this.description = description;
        this.tooltipHtml = tooltipHtml;
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

    public String getTooltipHtml() {
        return tooltipHtml;
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
