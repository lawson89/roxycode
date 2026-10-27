package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.ActionListener;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
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
    void testIncrementalAppendPreservesDocumentInstanceAndPreviousEntries() {
        MarkdownPane pane = new MarkdownPane();
        assertTrue(pane.getText().contains("welcome-card"));

        // 0 -> 1 transition replaces the welcome card
        pane.appendMessage("user", "First user question");
        Document docAfterFirst = pane.getDocument();
        assertFalse(pane.getText().contains("welcome-card"));
        assertTrue(pane.getText().contains("First user question"));

        // Subsequent appends mutate the existing HTMLDocument in-place without recreating it
        pane.appendToolLog("fileReadService.readFile('pom.xml')", true, null);
        assertSame(
                docAfterFirst,
                pane.getDocument(),
                "Second entry should mutate existing HTMLDocument in-place");

        pane.appendMessage("ai", "First AI answer with **bold** text");
        assertSame(
                docAfterFirst,
                pane.getDocument(),
                "Third entry should mutate existing HTMLDocument in-place");

        pane.appendMessage("user", "Second user question");
        pane.appendMessage("ai", "Second AI answer");
        assertSame(docAfterFirst, pane.getDocument());

        String html = pane.getText();
        assertFalse(html.contains("welcome-card"));
        assertTrue(html.contains("First user question"));
        assertTrue(html.contains("fileReadService.readFile"));
        assertTrue(html.contains("First AI answer"));
        assertTrue(html.contains("Second user question"));
        assertTrue(html.contains("Second AI answer"));
        assertTrue(html.contains("copy-cell-2"));
        assertTrue(html.contains("copy-cell-4"));
    }

    @Test
    void testInPlaceCopyFeedbackUpdatesAndRevertsAcrossMultipleAiMessagesPreservingCaret() {
        MarkdownPane pane = new MarkdownPane();
        pane.appendMessage("user", "Prompt 1");
        pane.appendMessage("ai", "AI Response 1");
        pane.appendMessage("user", "Prompt 2");
        pane.appendMessage("ai", "AI Response 2");

        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        Element cell1 = doc.getElement("copy-cell-1");
        Element cell3 = doc.getElement("copy-cell-3");
        assertNotNull(cell1, "copy-cell-1 should exist in HTMLDocument");
        assertNotNull(cell3, "copy-cell-3 should exist in HTMLDocument");

        // Set a custom caret position (not at 0 and not at end) to verify it is preserved
        int customCaret = 5;
        pane.setCaretPosition(customCaret);

        // Click copy on first AI message (index 1)
        pane.copyMessageMarkdown(1);
        assertSame(doc, pane.getDocument(), "In-place copy update must not recreate HTMLDocument");
        assertEquals(1, pane.getCopiedMessageIndex());
        assertEquals("AI Response 1", pane.getLastCopiedMarkdown());
        assertEquals(customCaret, pane.getCaretPosition(), "Caret position should be preserved");

        String htmlAfterFirstCopy = pane.getText();
        assertTrue(htmlAfterFirstCopy.contains("Copied!"));
        assertFalse(
                htmlAfterFirstCopy.contains("copy:1"),
                "Message 1 copy link should be replaced with Copied!");
        assertTrue(
                htmlAfterFirstCopy.contains("copy:3"),
                "Message 3 copy link should remain untouched");

        // Click copy on second AI message (index 3) before timer expires
        pane.copyMessageMarkdown(3);
        assertSame(doc, pane.getDocument(), "Second copy update must not recreate HTMLDocument");
        assertEquals(3, pane.getCopiedMessageIndex());
        assertEquals("AI Response 2", pane.getLastCopiedMarkdown());
        assertEquals(customCaret, pane.getCaretPosition(), "Caret position should be preserved");

        String htmlAfterSecondCopy = pane.getText();
        assertTrue(
                htmlAfterSecondCopy.contains("copy:1"),
                "Message 1 should revert to [Copy] link when message 3 is copied");
        assertFalse(
                htmlAfterSecondCopy.contains("copy:3"),
                "Message 3 should now show Copied! feedback");

        // Fire the feedback timer to verify in-place reversion of message 3
        Timer timer = pane.getCopyFeedbackTimer();
        assertNotNull(timer);
        for (ActionListener listener : timer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        timer.stop();

        assertEquals(-1, pane.getCopiedMessageIndex());
        assertEquals(
                customCaret,
                pane.getCaretPosition(),
                "Caret position should be preserved after timer reversion");
        String htmlAfterTimerRevert = pane.getText();
        assertFalse(
                htmlAfterTimerRevert.contains("Copied!"),
                "Copied! feedback should be removed after timer expires");
        assertTrue(htmlAfterTimerRevert.contains("copy:1"));
        assertTrue(htmlAfterTimerRevert.contains("copy:3"));
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

    @Test
    void testMarkdownTableDecoratedWhileChatCardStructuralTablesPreserved() {
        MarkdownPane pane = new MarkdownPane();
        pane.appendMessage(
                "ai",
                """
                | Col A | Col B |
                |---|---|
                | 1 | 2 |
                """);

        String html = pane.getText();
        assertTrue(
                html.contains(
                        """
                        class="md-table"
                        """
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        cellpadding="6"
                        """
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        cellspacing="0"
                        """
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        class="md-th"
                        """
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        class="md-td"
                        """
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        class="ai-entry"
                        """
                                .strip()),
                "Chat card layout table should remain intact");
        assertTrue(
                html.contains(
                        """
                        class="ai-accent"
                        """
                                .strip()),
                "Chat card 4px accent bar should remain intact");
    }

    @Test
    void testGetConversationMessagesFiltersOutToolLogsAndSystemMessages() {
        MarkdownPane pane = new MarkdownPane();
        assertTrue(pane.getConversationMessages().isEmpty());

        pane.appendMessage("user", "How does MemoryService work?");
        pane.appendToolLog("memoryService.search('test')", true, null);
        pane.appendMessage("ai", "It uses RipGrep across memory directories.");
        pane.appendMessage("system", "Started a new topic.");

        var transcript = pane.getConversationMessages();
        assertEquals(2, transcript.size());
        assertEquals("user", transcript.get(0).role());
        assertEquals("How does MemoryService work?", transcript.get(0).content());
        assertNotNull(transcript.get(0).timestamp());
        assertEquals("ai", transcript.get(1).role());
        assertEquals("It uses RipGrep across memory directories.", transcript.get(1).content());

        pane.clear();
        assertTrue(pane.getConversationMessages().isEmpty());
    }
}
