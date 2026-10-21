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
                            : "<a class='copy-action' href='copy:"
                                    + messageIndex
                                    + "'>&#128203; Copy</a>";
            actionCell =
                    "<td align='right' valign='middle' nowrap='nowrap'>" + actionContent + "</td>";
        }

        String template =
                """
				<table class="chat-entry ${entryClass}" width="100%" cellpadding="10" cellspacing="0" border="0">
				    <tr>
				        <td class="accent-bar ${accentClass}" width="4">&nbsp;</td>
				        <td class="message-body">
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
				    <tr><td height="4">&nbsp;</td></tr>
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

    public static String renderToolLog(
            String toolName, String output, boolean success, String error) {
        String header =
                (toolName == null || toolName.trim().isEmpty())
                        ? ""
                        : "<div class='tool-header'>"
                                + SwingHtmlUtils.escapeHtml(toolName)
                                + "</div>";
        String statusHtml;
        if (success) {
            statusHtml = "<div class=\"tool-result\">&#10003; Executed successfully</div>";
        } else {
            String safeError =
                    (error == null || error.trim().isEmpty())
                            ? "Unknown error"
                            : SwingHtmlUtils.escapeHtml(error);
            statusHtml = "<div class=\"tool-error\">&#10007; Error: " + safeError + "</div>";
        }
        String template =
                """
				<table class="tool-call" width="100%" cellpadding="8" cellspacing="0" border="0">
				    <tr>
				        <td class="accent-bar tool-accent" width="4">&nbsp;</td>
				        <td class="tool-body-container">
				            ${header}
				            <pre class="tool-body">${output}</pre>
				            ${statusHtml}
				        </td>
				    </tr>
				</table>
				<table width="100%" cellpadding="0" cellspacing="0" border="0">
				    <tr><td height="4">&nbsp;</td></tr>
				</table>
				""";

        Map<String, String> values =
                Map.of(
                        "header",
                        header,
                        "output",
                        output != null ? output : "",
                        "statusHtml",
                        statusHtml);

        return StringSubstitutor.replace(template, values);
    }
}
