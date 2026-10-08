package org.roxycode.app.ui;

/**
 * Native Java text block templates for chat HTML rendering conforming to Swing HTML 3.2.
 */
public class ChatHtmlTemplates {

    public static String renderChatEntry(String role, String content, String timestamp) {
        String entryClass = role + "-entry";
        String accentClass = role + "-accent";
        String leftHeader = role.equals("ai") ? "<span style=\"font-family: monospace; color: #888888; font-size: 10px;\">/\\_/\\</span>" : "&nbsp;";
        
        return """
            <table class="chat-entry %s" width="100%%" cellpadding="10" cellspacing="0" border="0">
                <tr>
                    <td class="accent-bar %s" width="4">&nbsp;</td>
                    <td class="message-body">
                        <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                            <tr>
                                <td align="left" valign="middle">%s</td>
                                <td align="right" valign="middle" class="timestamp">%s</td>
                            </tr>
                        </table>
                        <div class="content">%s</div>
                    </td>
                </tr>
            </table>
            <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                <tr><td height="4">&nbsp;</td></tr>
            </table>
            """.formatted(entryClass, accentClass, leftHeader, timestamp, content);
    }

    public static String renderDivider() {
        return "<hr class='chat-divider'/>";
    }

    public static String renderTimestamp(String timestamp) {
        return """
            <div class="chat-timestamp">%s</div>
            """.formatted(timestamp);
    }

    public static String renderToolLog(String toolName, String output) {
        String header = (toolName == null || toolName.trim().isEmpty()) ? "" : "<div class='tool-header'>" + toolName + "</div>";
        return """
            <table class="tool-call" width="100%%" cellpadding="8" cellspacing="0" border="0">
                <tr>
                    <td class="accent-bar tool-accent" width="4">&nbsp;</td>
                    <td class="tool-body-container">
                        %s
                        <div class="tool-body">%s</div>
                    </td>
                </tr>
            </table>
            <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                <tr><td height="4">&nbsp;</td></tr>
            </table>
            """.formatted(header, output);
    }
}
