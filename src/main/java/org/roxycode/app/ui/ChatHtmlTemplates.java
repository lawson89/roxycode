package org.roxycode.app.ui;

import java.util.Map;
import org.apache.commons.text.StringSubstitutor;

/** Native Java text block templates for chat HTML rendering conforming to Swing HTML 3.2. */
public class ChatHtmlTemplates {

  private static final String ROLE_AI = "ai";
  private static final String ROLE_USER = "user";
  private static final String ROLE_SYSTEM = "system";

  public static String renderChatEntry(String role, String content, String timestamp) {
    return renderChatEntry(role, content, timestamp, -1, false);
  }

  public static String renderChatEntry(
      String role, String content, String timestamp, int messageIndex, boolean copied) {
    String safeRole = SwingHtmlUtils.escapeHtml(role != null ? role : "");
    String entryClass = safeRole + "-entry";
    String accentClass = safeRole + "-accent";
    String safeTimestamp = SwingHtmlUtils.escapeHtml(timestamp != null ? timestamp : "");

    String indicator = "&nbsp;";
    if (ROLE_AI.equals(role)) {
      indicator =
          "<span style='font-family: monospace; color: #888888; font-size: 10px;'>ROXY /\\_/\\</span>";
    } else if (ROLE_USER.equals(role)) {
      indicator =
          "<span style='font-family: monospace; color: #888888; font-size: 10px;'>USER</span>";
    } else if (ROLE_SYSTEM.equals(role)) {
      indicator =
          "<span style='font-family: monospace; color: #888888; font-size: 10px;'>SYSTEM</span>";
    }

    String actionCell = "";
    if (ROLE_AI.equals(role) && messageIndex >= 0) {
      String actionContent =
          copied
              ? "<span class='copy-feedback'>&#10003; Copied!</span>"
              : "<a class='copy-action' href='copy:" + messageIndex + "'>&#128203; Copy</a>";
      actionCell = "<td align='right' valign='middle' nowrap='nowrap'>" + actionContent + "</td>";
    }

    String template =
        """
            <table class="chat-entry ${entryClass}" width="100%" cellpadding="10" cellspacing="0" border="0">
                <tr>
                    <td class="accent-bar ${accentClass}" width="4">&nbsp;</td>
                    <td class="message-body">
                        <table width="100%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                                <td align="left" valign="middle"><span class="timestamp">${timestamp}</span>&nbsp;&nbsp;${indicator}</td>
                                ${actionCell}
                            </tr>
                        </table>
                        <div class="content">${content}</div>
                    </td>
                </tr>
            </table>
            <table width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr><td height="4">&nbsp;</td></tr>
            </table>
            """;

    Map<String, String> values =
        Map.of(
            "entryClass", entryClass,
            "accentClass", accentClass,
            "timestamp", safeTimestamp,
            "indicator", indicator,
            "actionCell", actionCell,
            "content", content != null ? content : "");

    return StringSubstitutor.replace(template, values);
  }

  public static String renderDivider() {
    return "<hr class='chat-divider'/>";
  }

  public static String renderTimestamp(String timestamp) {
    String safeTimestamp = SwingHtmlUtils.escapeHtml(timestamp != null ? timestamp : "");
    String template =
        """
            <div class="chat-timestamp">${timestamp}</div>
            """;
    return StringSubstitutor.replace(template, Map.of("timestamp", safeTimestamp));
  }

  public static String renderToolLog(String toolName, String output) {
    String header =
        (toolName == null || toolName.trim().isEmpty())
            ? ""
            : "<div class='tool-header'>" + SwingHtmlUtils.escapeHtml(toolName) + "</div>";
    String template =
        """
            <table class="tool-call" width="100%" cellpadding="8" cellspacing="0" border="0">
                <tr>
                    <td class="accent-bar tool-accent" width="4">&nbsp;</td>
                    <td class="tool-body-container">
                        ${header}
                        <pre class="tool-body">${output}</pre>
                    </td>
                </tr>
            </table>
            <table width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr><td height="4">&nbsp;</td></tr>
            </table>
            """;

    Map<String, String> values =
        Map.of(
            "header", header,
            "output", output);

    return StringSubstitutor.replace(template, values);
  }
}
