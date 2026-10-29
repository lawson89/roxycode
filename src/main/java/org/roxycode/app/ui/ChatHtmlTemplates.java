package org.roxycode.app.ui;

public class ChatHtmlTemplates {

    private static final String ROLE_AI = "ai";
    private static final String ROLE_USER = "user";
    private static final String ROLE_SYSTEM = "system";
    private static final String CAT_EARS = "/\\_/\\";
    public static final String COPY_CELL_ID_PREFIX = "copy-cell-";

    public static String renderCopyActionHtml(int messageIndex, boolean copied) {
        return copied
                ? "<span class='copy-feedback'>&#10003; Copied!</span>"
                : "<a class='copy-action' href='copy:" + messageIndex + "'>[Copy]</a>";
    }

    public static String renderChatEntry(String role, String content, String timestamp, int messageIndex,
            boolean copied) {
        String safeRole = SwingHtmlUtils.escapeHtml(role != null ? role : "");
        String indicator = switch (role != null ? role : "") {
            case ROLE_AI -> "<span class='role-ai'>ROXY " + CAT_EARS + "</span>";
            case ROLE_USER -> "<span class='role-user'>USER</span>";
            case ROLE_SYSTEM -> "<span class='role-system'>SYSTEM</span>";
            default -> "&nbsp;";
        };
        String actionCell = (ROLE_AI.equals(role) && messageIndex >= 0)
                ? "<td id='" + COPY_CELL_ID_PREFIX + messageIndex + "' align='right' valign='middle' nowrap='nowrap'>"
                        + renderCopyActionHtml(messageIndex, copied) + "</td>"
                : "";

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
                .formatted(safeRole, safeRole, indicator, SwingHtmlUtils.escapeHtml(timestamp != null ? timestamp : ""),
                        actionCell, content != null ? content : "");
    }

    public static String renderToolLog(String output, boolean success, String error, String timestamp) {
        String statusHtml = success
                ? "<span class='tool-result'>&#10003; Executed successfully</span>"
                : "<span class='tool-error'>&#10007; Error: " + ((error == null || error.trim().isEmpty())
                        ? "Unknown error"
                        : SwingHtmlUtils.escapeHtml(error)) + "</span>";
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
                .formatted(SwingHtmlUtils.escapeHtml(timestamp != null ? timestamp : ""), statusHtml,
                        output != null ? output : "");
    }

    public static String renderWelcomeCard() {
        return """
                <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td width="100%%" style="padding: 12px 16px;">
                            <div class="welcome-title">ROXY %s &mdash; Ready to Assist</div>
                            <div class="welcome-hint" style="margin-top: 6px;">
                                Ask questions about your codebase, plan architectural changes, or implement features with automated build &amp; test verification.
                            </div>
                            <table width="100%%" cellpadding="2" cellspacing="0" border="0" style="margin-top: 8px;">
                                <tr>
                                    <td class="welcome-hint">&bull; <b>Enter</b> to send message, <b>Shift+Enter</b> for new line</td>
                                </tr>
                                <tr>
                                    <td class="welcome-hint">&bull; <b>Ctrl+V</b> or click <b>Attach Image</b> to include screenshots &amp; diagrams</td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
                """
                .formatted(CAT_EARS);
    }
}
