package org.roxycode.app.ui;

/**
 * Native Java text block templates for chat HTML rendering.
 * Replaces Pebble templates to reduce external dependencies.
 */
public class ChatHtmlTemplates {

    public static String renderChatEntry(String role, String content, String timestamp) {
        return """
            <div class="chat-entry %s-entry">
                <div class="content">%s</div>
                <div class="timestamp">%s</div>
            </div>
            """.formatted(role, content, timestamp);
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
        if (toolName == null || toolName.trim().isEmpty()) {
            return """
                <div class="tool-call">
                    <div class="tool-body">%s</div>
                </div>
                """.formatted(output);
        }
        return """
            <div class="tool-call">
                <div class="tool-header">%s</div>
                <div class="tool-body">%s</div>
            </div>
            """.formatted(toolName, output);
    }
}
