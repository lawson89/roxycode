package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatHtmlTemplatesTest {

    @Test
    void testRenderChatEntry() {
        String role = "user";
        String content = "<p>Hello</p>";
        String timestamp = "12:00:00";
        String result = ChatHtmlTemplates.renderChatEntry(role, content, timestamp);
        
        assertTrue(result.contains("user-entry"));
        assertTrue(result.contains("<p>Hello</p>"));
        assertTrue(result.contains("12:00:00"));
    }

    @Test
    void testRenderDivider() {
        String result = ChatHtmlTemplates.renderDivider();
        assertTrue(result.contains("chat-divider"));
    }

    @Test
    void testRenderTimestamp() {
        String timestamp = "12:00:00";
        String result = ChatHtmlTemplates.renderTimestamp(timestamp);
        assertTrue(result.contains("chat-timestamp"));
        assertTrue(result.contains("12:00:00"));
    }

    @Test
    void testRenderToolLog() {
        String toolName = "git";
        String output = "diff output";
        String result = ChatHtmlTemplates.renderToolLog(toolName, output);
        
        assertTrue(result.contains("tool-call"));
        assertTrue(result.contains("git"));
        assertTrue(result.contains("diff output"));
    }

    @Test
    void testRenderToolLogEmptyToolName() {
        String toolName = "";
        String output = "jexl output";
        String result = ChatHtmlTemplates.renderToolLog(toolName, output);
        
        assertTrue(result.contains("tool-call"));
        assertFalse(result.contains("tool-header"));
        assertTrue(result.contains("jexl output"));
    }

}