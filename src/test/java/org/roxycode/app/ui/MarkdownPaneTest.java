package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.event.HyperlinkEvent;
import org.junit.jupiter.api.Test;

class MarkdownPaneTest {

    @Test
    void testEmptyStateRendersWelcomeCard() {
        MarkdownPane pane = new MarkdownPane();
        String emptyHtml = pane.getText();
        assertTrue(
                emptyHtml.contains("welcome-card"),
                "Empty MarkdownPane should render welcome card");
        assertTrue(emptyHtml.contains("Ready to Assist"));

        pane.appendMessage("user", "Hello");
        assertFalse(
                pane.getText().contains("welcome-card"),
                "Welcome card should disappear once message is appended");

        pane.clear();
        assertTrue(
                pane.getText().contains("welcome-card"),
                "Clearing MarkdownPane should restore welcome card");
    }

    @Test
    void testCopyHyperlinkCopiesRawMarkdownAndShowsFeedback() {
        MarkdownPane pane = new MarkdownPane();
        String userPrompt = "How do I print in Java?";
        String aiMarkdown = "Here is **bold** markdown and code: System.out.println(42);";

        pane.appendMessage("user", userPrompt);
        pane.appendMessage("ai", aiMarkdown);

        String initialHtml = pane.getText();
        assertTrue(initialHtml.contains("copy:1"), "AI message at index 1 should have copy:1 link");
        assertTrue(initialHtml.contains("[Copy]"), "AI message should render [Copy] text");
        assertFalse(
                initialHtml.contains("copy:0"),
                "User message at index 0 should not have copy link");
        assertEquals(-1, pane.getCopiedMessageIndex());

        // Simulate clicking the copy link for message 1
        pane.fireHyperlinkUpdate(
                new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "copy:1"));

        assertEquals(aiMarkdown, pane.getLastCopiedMarkdown());
        assertEquals(1, pane.getCopiedMessageIndex());
        assertTrue(
                pane.getText().contains("Copied!"),
                "Rendered HTML should show Copied! feedback after clicking copy");

        pane.clear();
        assertEquals(-1, pane.getCopiedMessageIndex());
    }

    @Test
    void testAppendToolLogRendersSuccessAndErrorStatus() {
        MarkdownPane pane = new MarkdownPane();
                pane.appendToolLog("var x = 1;", true, null);
        pane.appendToolLog("if (", false, "Syntax Error: <eof>");

        String html = pane.getText();
        assertTrue(html.contains("Executed successfully"));
        assertTrue(html.contains("Error: Syntax Error: &lt;eof&gt;"));
        assertFalse(html.contains("<eof>"));
    }
}
