package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatHtmlTemplatesTest {

    @Test
    void testRenderChatEntry() {
        String role = "user";
        String content = "<p>Hello</p>";
        String timestamp = "12:00:00";
        String result = ChatHtmlTemplates.renderChatEntry(role, content, timestamp, -1, false);

        assertTrue(result.contains("<table class=\"user-entry\""));
        assertTrue(result.contains("<td class=\"user-accent\" width=\"4\">&nbsp;</td>"));
        assertTrue(result.contains("<td class=\"message-body\" width=\"100%\">"));
        assertFalse(result.contains("chat-entry"));
        assertFalse(result.contains("accent-bar"));
        assertTrue(result.contains("role-user"));
        assertTrue(result.contains("margin-top: 4px;"));
        assertTrue(result.contains("<p>Hello</p>"));
        assertTrue(result.contains("12:00:00"));
        assertFalse(result.contains("copy-action"));
    }

    @Test
    void testRenderAiChatEntryWithCopyAction() {
        String result =
                ChatHtmlTemplates.renderChatEntry("ai", "<p>Response</p>", "12:00:00", 2, false);

        assertTrue(result.contains("ai-entry"));
        assertTrue(result.contains("href='copy:2'"));
        assertTrue(result.contains("copy-action"));
        assertTrue(result.contains("&#128203; Copy"));
        assertFalse(result.contains("copy-feedback"));
    }

    @Test
    void testRenderAiChatEntryWithCopiedFeedback() {
        String result =
                ChatHtmlTemplates.renderChatEntry("ai", "<p>Response</p>", "12:00:00", 2, true);

        assertTrue(result.contains("ai-entry"));
        assertTrue(result.contains("copy-feedback"));
        assertTrue(result.contains("&#10003; Copied!"));
        assertFalse(result.contains("href='copy:2'"));
    }

    @Test
    void testRenderUserChatEntryDoesNotIncludeCopyAction() {
        String result =
                ChatHtmlTemplates.renderChatEntry("user", "<p>Prompt</p>", "12:00:00", 0, false);

        assertTrue(result.contains("user-entry"));
        assertFalse(result.contains("copy-action"));
        assertFalse(result.contains("href='copy:0'"));
    }

    @Test
    void testRenderToolLog() {
        String toolName = "git";
        String output = "diff output";
        String result = ChatHtmlTemplates.renderToolLog(toolName, output, true, null);

        assertTrue(result.contains("<table class=\"tool-call\""));
        assertTrue(result.contains("<td class=\"tool-accent\" width=\"4\">&nbsp;</td>"));
        assertTrue(result.contains("<td class=\"tool-body-container\" width=\"100%\">"));
        assertFalse(result.contains("accent-bar"));
        assertTrue(result.contains("git"));
        assertTrue(result.contains("diff output"));
        assertTrue(
                result.contains("class=")
                        && result.contains("tool-result")
                        && result.contains("&#10003; Executed successfully"));
        assertFalse(result.contains("tool-error"));
    }

    @Test
    void testRenderToolLogSuccessAndFailure() {
        String successHtml = ChatHtmlTemplates.renderToolLog("jexl", "1 + 1", true, null);
        assertTrue(
                successHtml.contains("tool-result")
                        && successHtml.contains("&#10003; Executed successfully"));
        assertFalse(successHtml.contains("tool-error"));

        String failureHtml =
                ChatHtmlTemplates.renderToolLog(
                        "jexl", "bad()", false, "Syntax Error: <unexpected> & token");
        assertTrue(
                failureHtml.contains("tool-error")
                        && failureHtml.contains(
                                "&#10007; Error: Syntax Error: &lt;unexpected&gt; &amp; token"));
        assertFalse(failureHtml.contains("<unexpected>"));
        assertFalse(failureHtml.contains("tool-result"));

        String fallbackHtml = ChatHtmlTemplates.renderToolLog("jexl", "bad()", false, "   ");
        assertTrue(
                fallbackHtml.contains("tool-error")
                        && fallbackHtml.contains("&#10007; Error: Unknown error"));
    }

    @Test
    void testRenderToolLogEmptyToolName() {
        String toolName = "";
        String output = "jexl output";
        String result = ChatHtmlTemplates.renderToolLog(toolName, output, true, null);

        assertTrue(result.contains("tool-call"));
        assertFalse(result.contains("tool-header"));
        assertTrue(result.contains("jexl output"));
    }

    @Test
    void testHtmlEscapingInTemplates() {
        String toolResult =
                ChatHtmlTemplates.renderToolLog("<script>alert(1)</script>", "output", true, null);
        assertFalse(toolResult.contains("<script>"));
        assertTrue(toolResult.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));

        String entryResult =
                ChatHtmlTemplates.renderChatEntry(
                        "user<bad>", "<p>Safe</p>", "12:00<img src=x>", -1, false);
        assertTrue(entryResult.contains("user&lt;bad&gt;-entry"));
        assertTrue(entryResult.contains("12:00&lt;img src=x&gt;"));
        assertFalse(entryResult.contains("<img src=x>"));
    }
}
