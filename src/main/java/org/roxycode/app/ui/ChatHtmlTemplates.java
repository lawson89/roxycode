package org.roxycode.app.ui;

import org.apache.commons.text.StringSubstitutor;
import java.util.Map;

/**
 * Native Java text block templates for chat HTML rendering conforming to Swing HTML 3.2.
 */
public class ChatHtmlTemplates {

    public static String renderChatEntry(String role, String content, String timestamp) {
        String entryClass = role + "-entry";
        String accentClass = role + "-accent";
        
        String indicator = "&nbsp;";
        if (role.equals("ai")) {
            indicator = "<span style='font-family: monospace; color: #888888; font-size: 10px;'>ROXY /\\_/\\</span>";
        } else if (role.equals("user")) {
            indicator = "<span style='font-family: monospace; color: #888888; font-size: 10px;'>USER</span>";
        } else if (role.equals("system")) {
            indicator = "<span style='font-family: monospace; color: #888888; font-size: 10px;'>SYSTEM</span>";
        }
        
        String template = """
            <table class="chat-entry ${entryClass}" width="100%" cellpadding="10" cellspacing="0" border="0">
                <tr>
                    <td class="accent-bar ${accentClass}" width="4">&nbsp;</td>
                    <td class="message-body">
                        <table width="100%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                                <td align="left" valign="middle"><span class="timestamp">${timestamp}</span>&nbsp;&nbsp;${indicator}</td>
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

        Map<String, String> values = Map.of(
            "entryClass", entryClass,
            "accentClass", accentClass,
            "timestamp", timestamp,
            "indicator", indicator,
            "content", content
        );

        return StringSubstitutor.replace(template, values);
    }

    public static String renderDivider() {
        return "<hr class='chat-divider'/>";
    }

    public static String renderTimestamp(String timestamp) {
        String template = """
            <div class="chat-timestamp">${timestamp}</div>
            """;
        return StringSubstitutor.replace(template, Map.of("timestamp", timestamp));
    }

    public static String renderToolLog(String toolName, String output) {
        String header = (toolName == null || toolName.trim().isEmpty()) ? "" : "<div class='tool-header'>" + toolName + "</div>";
        String template = """
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

        Map<String, String> values = Map.of(
            "header", header,
            "output", output
        );

        return StringSubstitutor.replace(template, values);
    }
}
