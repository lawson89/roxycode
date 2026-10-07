package org.roxycode.app.ui;

/**
 * Native Java text block templates for chat HTML rendering.
 * Replaces Pebble templates to reduce external dependencies.
 */
public class ChatHtmlTemplates {

    public static String renderChatEntry(String role, String content, String timestamp) {
        String entryClass = role + "-entry";
        String accentClass = role + "-accent";
        return """
            <table class="chat-entry %s" width="100%%" cellpadding="10" cellspacing="0">
                <tr>
                    <td class="accent-bar %s" width="4">&nbsp;</td>
                    <td class="message-body">
                        <div class="timestamp">%s</div>
                        <div class="content">%s</div>
                    </td>
                </tr>
            </table>
            <div style="font-size: 4px;">&nbsp;</div>
            """.formatted(entryClass, accentClass, timestamp, content);
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
            <table class="tool-call" width="100%%" cellpadding="8" cellspacing="0">
                <tr>
                    <td class="accent-bar tool-accent" width="4">&nbsp;</td>
                    <td class="tool-body-container">
                        %s
                        <div class="tool-body">%s</div>
                    </td>
                </tr>
            </table>
            <div style="font-size: 4px;">&nbsp;</div>
            """.formatted(header, output);
    }
}
