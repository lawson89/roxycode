package org.roxycode.app.ui;

import org.apache.commons.lang3.StringUtils;

public class ChatHtmlTemplates {

    private static final String ROLE_AI = "ai";
    private static final String ROLE_USER = "user";
    private static final String ROLE_SYSTEM = "system";
    private static final String CAT_EARS = "/" + (char) 92 + "_/" + (char) 92;
    public static final String COPY_CELL_ID_PREFIX = "copy-cell-";

    public static String renderCopyActionHtml(int messageIndex, boolean copied) {
        if (copied) {
            return "<span class='copy-feedback'>&#10003; Copied!</span>";
        }
        return "<a class='copy-action' href='copy:" + messageIndex + "'>[Copy]</a>";
    }

    public static String renderChatEntry(String role, String content, String timestamp, int messageIndex,
            boolean copied) {
        String normalizedRole = StringUtils.defaultString(role);
        String safeRole = SwingHtmlUtils.escapeHtml(normalizedRole);
        String indicator = switch (normalizedRole) {
            case ROLE_AI -> "<span class='role-ai'>ROXY " + CAT_EARS + "</span>";
            case ROLE_USER -> "<span class='role-user'>USER</span>";
            case ROLE_SYSTEM -> "<span class='role-system'>SYSTEM</span>";
            default -> "&nbsp;";
        };
        String actionCell = "";
        if (ROLE_AI.equals(role) && messageIndex >= 0) {
            actionCell = "<td id='" + COPY_CELL_ID_PREFIX + messageIndex
                    + "' align='right' valign='middle' nowrap='nowrap'>" + renderCopyActionHtml(messageIndex, copied)
                    + "</td>";
        }

        return """
                <table class="%s-entry" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="%s-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td class="message-body" width="100%%" style="padding: 8px 12px;">
                            <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                                <tr>
                                    <td align="left" valign="middle">%s&nbsp;&nbsp;<span class="timestamp">%s</span></td>
                                    %s
                                </tr>
                            </table>
                            <div class="content" style="margin-top: 4px;">%s</div>
                        </td>
                    </tr>
                </table>
                <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr><td height="8">&nbsp;</td></tr>
                </table>
                """
                .formatted(safeRole, safeRole, indicator,
                        SwingHtmlUtils.escapeHtml(StringUtils.defaultString(timestamp)), actionCell,
                        StringUtils.defaultString(content));
    }

    public static String renderToolLog(String output, boolean success, String error, String timestamp) {
        String statusHtml;
        if (success) {
            statusHtml = "<span class='tool-result'>&#10003; Executed successfully</span>";
        } else {
            String errorText;
            if (error == null || error.trim().isEmpty()) {
                errorText = "Unknown error";
            } else {
                errorText = SwingHtmlUtils.escapeHtml(error);
            }
            statusHtml = "<span class='tool-error'>&#10007; Error: " + errorText + "</span>";
        }
        return """
                <table class="tool-call" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="tool-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td class="tool-body-container" width="100%%" style="padding: 6px 12px 8px 12px;">
                            <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                                <tr>
                                    <td align="left" valign="middle"><span class="role-tool">&#9889; JEXL TOOL</span>&nbsp;&nbsp;<span class="timestamp">%s</span></td>
                                    <td align="right" valign="middle">%s</td>
                                </tr>
                            </table>
                            <pre class="tool-body">%s</pre>
                        </td>
                    </tr>
                </table>
                <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr><td height="8">&nbsp;</td></tr>
                </table>
                """
                .formatted(SwingHtmlUtils.escapeHtml(StringUtils.defaultString(timestamp)), statusHtml,
                        StringUtils.defaultString(output));
    }

    public static String renderWelcomeCard() {
        return renderWelcomeCard(false);
    }

    public static String renderWelcomeCard(boolean isTaskMode) {
        if (isTaskMode) {
            return """
                    <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                        <tr>
                            <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                            <td width="100%%" style="padding: 12px 16px;">
                                <div class="welcome-title">ROXY %s &mdash; Autonomous Feature Development (Plan &rarr; Code &rarr; Review)</div>
                                <div class="welcome-hint" style="margin-top: 6px;">
                                    Build features, refactor modules, and fix bugs through a structured 3-phase lifecycle with human-in-the-loop approval and automated verification:
                                </div>
                                <table width="100%%" cellpadding="2" cellspacing="0" border="0" style="margin-top: 8px;">
                                    <tr>
                                        <td class="welcome-hint">1. <b>Plan</b>: Describe the feature or bugfix; Roxy drafts a step-by-step implementation plan for your approval.</td>
                                    </tr>
                                    <tr>
                                        <td class="welcome-hint">2. <b>Code</b>: Once you click <b>Approve</b>, Roxy edits files, compiles, and runs unit tests autonomously.</td>
                                    </tr>
                                    <tr>
                                        <td class="welcome-hint">3. <b>Review</b>: Roxy audits the git diff and test results before requesting final sign-off.</td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                    """
                    .formatted(CAT_EARS);
        }
        return """
                <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td width="100%%" style="padding: 12px 16px;">
                            <div class="welcome-title">ROXY %s &mdash; Technical Mentor (Read-Only Explore Mode)</div>
                            <div class="welcome-hint" style="margin-top: 6px;">
                                Explore your codebase, ask architecture questions, and debug issues safely without modifying any files.
                            </div>
                            <table width="100%%" cellpadding="2" cellspacing="0" border="0" style="margin-top: 8px;">
                                <tr>
                                    <td class="welcome-hint">&bull; <i>"How does the workflow transition lifecycle work?"</i></td>
                                </tr>
                                <tr>
                                    <td class="welcome-hint">&bull; <i>"Explain how @PromptService.java assembles the system prompt."</i></td>
                                </tr>
                                <tr>
                                    <td class="welcome-hint">&bull; Type <b>@</b> or click <b>@ Mention</b> to reference files &bull; <b>Enter</b> to send, <b>Shift+Enter</b> for newline &bull; <b>Ctrl+V</b> to paste images</td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
                """
                .formatted(CAT_EARS);
    }
}
