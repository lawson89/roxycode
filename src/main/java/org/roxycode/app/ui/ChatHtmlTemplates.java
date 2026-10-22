package org.roxycode.app.ui;

import java.util.Map;
import org.apache.commons.text.StringSubstitutor;

/** Native Java text block templates for chat HTML rendering conforming to Swing HTML 3.2. */
public class ChatHtmlTemplates {

    private static final String ROLE_AI = "ai";
    private static final String ROLE_USER = "user";
    private static final String ROLE_SYSTEM = "system";

    public static String renderChatEntry(
            String role, String content, String timestamp, int messageIndex, boolean copied) {
        String safeRole = SwingHtmlUtils.escapeHtml(role != null ? role : "");
        String entryClass = safeRole + "-entry";
        String accentClass = safeRole + "-accent";
        String safeTimestamp = SwingHtmlUtils.escapeHtml(timestamp != null ? timestamp : "");

        String indicator = "&nbsp;";
        if (ROLE_AI.equals(role)) {
            indicator = "<span class='role-ai'>ROXY /\\_/\\</span>";
        } else if (ROLE_USER.equals(role)) {
            indicator = "<span class='role-user'>USER</span>";
        } else if (ROLE_SYSTEM.equals(role)) {
            indicator = "<span class='role-system'>SYSTEM</span>";
        }

        String actionCell = "";
        if (ROLE_AI.equals(role) && messageIndex >= 0) {
            String actionContent =
                    copied
                            ? "<span class='copy-feedback'>&#10003; Copied!</span>"
                            : "<a class='copy-action' href='copy:" + messageIndex + "'>[Copy]</a>";
            actionCell =
                    "<td align='right' valign='middle' nowrap='nowrap'>" + actionContent + "</td>";
        }

        String template =
                """
				<table class="${entryClass}" width="100%" cellpadding="0" cellspacing="0" border="0">
				    <tr>
				        <td class="${accentClass}" width="4" nowrap="nowrap">&nbsp;</td>
				        <td class="message-body" width="100%" style="padding: 8px 12px;">
				            <table width="100%" cellpadding="0" cellspacing="0" border="0">
				                <tr>
				                    <td align="left" valign="middle">${indicator}&nbsp;&nbsp;<span class="timestamp">${timestamp}</span></td>
				                    ${actionCell}
				                </tr>
				            </table>
				            <div class="content" style="margin-top: 4px;">${content}</div>
				        </td>
				    </tr>
				</table>
				<table width="100%" cellpadding="0" cellspacing="0" border="0">
				    <tr><td height="8">&nbsp;</td></tr>
				</table>
				""";

        Map<String, String> values =
                Map.of(
                        "entryClass",
                        entryClass,
                        "accentClass",
                        accentClass,
                        "timestamp",
                        safeTimestamp,
                        "indicator",
                        indicator,
                        "actionCell",
                        actionCell,
                        "content",
                        content != null ? content : "");

        return StringSubstitutor.replace(template, values);
    }

    public static String renderToolLog(String output, boolean success, String error) {
        String statusHtml;
        if (success) {
            statusHtml = "<div class='tool-result'>&#10003; Executed successfully</div>";
        } else {
            String safeError =
                    (error == null || error.trim().isEmpty())
                            ? "Unknown error"
                            : SwingHtmlUtils.escapeHtml(error);
            statusHtml = "<div class='tool-error'>&#10007; Error: " + safeError + "</div>";
        }
        String template =
                """
				<table class="tool-call" width="100%" cellpadding="0" cellspacing="0" border="0">
				    <tr>
				        <td class="tool-accent" width="4" nowrap="nowrap">&nbsp;</td>
				        <td class="tool-body-container" width="100%" style="padding: 8px 12px;">
				            <pre class="tool-body">${output}</pre>
				            ${statusHtml}
				        </td>
				    </tr>
				</table>
				<table width="100%" cellpadding="0" cellspacing="0" border="0">
				    <tr><td height="8">&nbsp;</td></tr>
				</table>
				""";

        Map<String, String> values =
                Map.of("output", output != null ? output : "", "statusHtml", statusHtml);

        return StringSubstitutor.replace(template, values);
    }

    public static String renderWelcomeCard() {
        return """
				<table class="welcome-card" width="100%" cellpadding="0" cellspacing="0" border="0">
				    <tr>
				        <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
				        <td width="100%" style="padding: 12px 16px;">
				            <div class="welcome-title">ROXY /\\_/\\ &mdash; Ready to Assist</div>
				            <div class="welcome-hint" style="margin-top: 6px;">
				                Ask questions about your codebase, plan architectural changes, or implement features with automated build &amp; test verification.
				            </div>
				            <table width="100%" cellpadding="2" cellspacing="0" border="0" style="margin-top: 8px;">
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
				""";
    }
}
