package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import javax.swing.JEditorPane;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import org.junit.jupiter.api.Test;

class ChatHtmlTemplatesTest {

    @Test
    void testRenderChatEntry() {
        String result =
                ChatHtmlTemplates.renderChatEntry("user", "<p>Hello</p>", "12:00:00", -1, false);
        assertContainsAll(
                result,
                "user-entry",
                "user-accent",
                "nowrap",
                "message-body",
                "padding: 8px 12px;",
                "role-user",
                "margin-top: 4px;",
                "<p>Hello</p>",
                "12:00:00");
        for (String absent :
                new String[] {"chat-entry", "accent-bar", "copy-action", "copy-cell-"}) {
            assertFalse(result.contains(absent));
        }
    }

    @Test
    void testRenderCopyActionHtml() {
        assertEquals(
                "<a class='copy-action' href='copy:3'>[Copy]</a>",
                ChatHtmlTemplates.renderCopyActionHtml(3, false));
        assertEquals(
                "<span class='copy-feedback'>&#10003; Copied!</span>",
                ChatHtmlTemplates.renderCopyActionHtml(3, true));
    }

    @Test
    void testRenderAiChatEntryWithCopyAction() {
        String result =
                ChatHtmlTemplates.renderChatEntry("ai", "<p>Response</p>", "12:00:00", 2, false);
        assertContainsAll(
                result, "ai-entry", "id='copy-cell-2'", "href='copy:2'", "copy-action", "[Copy]");
        assertFalse(result.contains("&#128203;"));
        assertFalse(result.contains("copy-feedback"));
    }

    @Test
    void testRenderAiChatEntryWithCopiedFeedback() {
        String result =
                ChatHtmlTemplates.renderChatEntry("ai", "<p>Response</p>", "12:00:00", 2, true);
        assertContainsAll(
                result, "ai-entry", "id='copy-cell-2'", "copy-feedback", "&#10003; Copied!");
        assertFalse(result.contains("href='copy:2'"));
    }

    @Test
    void testRenderUserChatEntryDoesNotIncludeCopyAction() {
        String result =
                ChatHtmlTemplates.renderChatEntry("user", "<p>Prompt</p>", "12:00:00", 0, false);
        assertTrue(result.contains("user-entry"));
        for (String absent : new String[] {"copy-action", "href='copy:0'", "id='copy-cell-0'"}) {
            assertFalse(result.contains(absent));
        }
    }

    @Test
    void testRenderToolLog() {
        String result = ChatHtmlTemplates.renderToolLog("diff output", true, null);
        assertContainsAll(
                result,
                "tool-call",
                "tool-accent",
                "tool-body-container",
                "diff output",
                "tool-result",
                "&#10003; Executed successfully");
        for (String absent : new String[] {"accent-bar", "tool-header", "tool-error"}) {
            assertFalse(result.contains(absent));
        }
    }

    @Test
    void testRenderToolLogSuccessAndFailure() {
        String successHtml = ChatHtmlTemplates.renderToolLog("1 + 1", true, null);
        assertContainsAll(successHtml, "tool-result", "&#10003; Executed successfully");
        assertFalse(successHtml.contains("tool-error"));

        String failureHtml =
                ChatHtmlTemplates.renderToolLog(
                        "bad()", false, "Syntax Error: <unexpected> & token");
        assertContainsAll(
                failureHtml,
                "tool-error",
                "&#10007; Error: Syntax Error: &lt;unexpected&gt; &amp; token");
        assertFalse(failureHtml.contains("<unexpected>"));
        assertFalse(failureHtml.contains("tool-result"));

        String fallbackHtml = ChatHtmlTemplates.renderToolLog("bad()", false, "   ");
        assertContainsAll(fallbackHtml, "tool-error", "&#10007; Error: Unknown error");
    }

    @Test
    void testHtmlEscapingInTemplates() {
        String toolResult =
                ChatHtmlTemplates.renderToolLog("output", false, "<script>alert(1)</script>");
        assertFalse(toolResult.contains("<script>"));
        assertTrue(toolResult.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));

        String entryResult =
                ChatHtmlTemplates.renderChatEntry(
                        "user<bad>", "<p>Safe</p>", "12:00<img src=x>", -1, false);
        assertContainsAll(entryResult, "user&lt;bad&gt;-entry", "12:00&lt;img src=x&gt;");
        assertFalse(entryResult.contains("<img src=x>"));
    }

    @Test
    void testRenderWelcomeCard() {
        assertContainsAll(
                ChatHtmlTemplates.renderWelcomeCard(),
                "welcome-card",
                "ai-accent",
                "welcome-title",
                "welcome-hint",
                "Enter",
                "Shift+Enter",
                "Attach Image");
    }

    @Test
    void testAccentCellRetainsPositiveMinimumSpanWithWrappingEditorKit() {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        SwingHtmlUtils.applyTheme(pane, 10);
        pane.setText(
                "<html><body>"
                        + ChatHtmlTemplates.renderChatEntry("ai", "<p>Hi</p>", "12:00", 0, false)
                        + "</body></html>");

        ViewFactory factory = pane.getEditorKit().getViewFactory();
        Element accentTd =
                SwingTestUtils.findTdByClass(
                        pane.getDocument().getDefaultRootElement(), "ai-accent");
        assertNotNull(accentTd);
        assertTrue(factory.create(accentTd).getMinimumSpan(View.X_AXIS) > 0);
    }
}
