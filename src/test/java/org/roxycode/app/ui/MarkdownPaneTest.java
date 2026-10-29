package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.awt.event.ActionListener;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import org.junit.jupiter.api.Test;

class MarkdownPaneTest {

    @Test
    void testEmptyStateRendersWelcomeCardAndConfiguresReadOnlyCursorAndCaret() {
        MarkdownPane pane = new MarkdownPane();
        assertContainsAll(pane.getText(), "welcome-card", "Ready to Assist");
        pane.appendMessage("user", "Hello selectable message");
        assertFalse(pane.getText().contains("welcome-card"));

        pane.selectAll();
        assertNotNull(pane.getSelectedText());
        assertTrue(pane.getSelectedText().contains("Hello selectable message"));

        pane.clear();
        assertTrue(pane.getText().contains("welcome-card"));
    }

    @Test
    void testCopyHyperlinkCopiesRawMarkdownAndShowsFeedback() {
        MarkdownPane pane = new MarkdownPane();
        String aiMarkdown = "Here is **bold** markdown and code: System.out.println(42);";
        pane.appendMessage("user", "How do I print in Java?");
        pane.appendMessage("ai", aiMarkdown);

        String initialHtml = pane.getText();
        assertContainsAll(initialHtml, "copy:1", "[Copy]");
        assertFalse(initialHtml.contains("copy:0"));
        assertEquals(-1, pane.getCopiedMessageIndex());

        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "copy:1"));
        assertEquals(aiMarkdown, pane.getLastCopiedMarkdown());
        assertEquals(1, pane.getCopiedMessageIndex());
        assertTrue(pane.getText().contains("Copied!"));

        pane.clear();
        assertEquals(-1, pane.getCopiedMessageIndex());
    }

    @Test
    void testIncrementalAppendPreservesDocumentInstanceAndPreviousEntries() {
        MarkdownPane pane = new MarkdownPane();
        assertTrue(pane.getText().contains("welcome-card"));

        pane.appendMessage("user", "First user question");
        Document doc = pane.getDocument();
        assertFalse(pane.getText().contains("welcome-card"));
        assertTrue(pane.getText().contains("First user question"));

        pane.appendToolLog("fileReadService.readFile('pom.xml')", true, null);
        pane.appendMessage("ai", "First AI answer with **bold** text");
        pane.appendMessage("user", "Second user question");
        pane.appendMessage("ai", "Second AI answer");
        assertSame(doc, pane.getDocument());

        String html = pane.getText();
        assertFalse(html.contains("welcome-card"));
        assertContainsAll(html, "First user question", "fileReadService.readFile", "First AI answer",
                "Second user question", "Second AI answer", "copy-cell-2", "copy-cell-4");
    }

    @Test
    void testInPlaceCopyFeedbackUpdatesAndRevertsAcrossMultipleAiMessagesPreservingCaret() {
        MarkdownPane pane = new MarkdownPane();
        pane.appendMessage("user", "Prompt 1");
        pane.appendMessage("ai", "AI Response 1");
        pane.appendMessage("user", "Prompt 2");
        pane.appendMessage("ai", "AI Response 2");

        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        assertNotNull(doc.getElement("copy-cell-1"));
        assertNotNull(doc.getElement("copy-cell-3"));

        int customCaret = 5;
        pane.setCaretPosition(customCaret);

        pane.copyMessageMarkdown(1);
        assertSame(doc, pane.getDocument());
        assertEquals(1, pane.getCopiedMessageIndex());
        assertEquals("AI Response 1", pane.getLastCopiedMarkdown());
        assertEquals(customCaret, pane.getCaretPosition());
        assertContainsAll(pane.getText(), "Copied!", "copy:3");
        assertFalse(pane.getText().contains("copy:1"));

        pane.copyMessageMarkdown(3);
        assertSame(doc, pane.getDocument());
        assertEquals(3, pane.getCopiedMessageIndex());
        assertEquals("AI Response 2", pane.getLastCopiedMarkdown());
        assertEquals(customCaret, pane.getCaretPosition());
        assertTrue(pane.getText().contains("copy:1"));
        assertFalse(pane.getText().contains("copy:3"));

        Timer timer = pane.getCopyFeedbackTimer();
        assertNotNull(timer);
        for (ActionListener listener : timer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        timer.stop();

        assertEquals(-1, pane.getCopiedMessageIndex());
        assertEquals(customCaret, pane.getCaretPosition());
        assertFalse(pane.getText().contains("Copied!"));
        assertContainsAll(pane.getText(), "copy:1", "copy:3");
    }

    @Test
    void testAppendToolLogRendersSuccessAndErrorStatus() {
        MarkdownPane pane = new MarkdownPane();
        pane.appendToolLog("var x = 1;", true, null);
        pane.appendToolLog("if (", false, "Syntax Error: <eof>");
        pane.appendToolLog("bad()", false, "   ");

        String html = pane.getText();
        assertContainsAll(html, "Executed successfully", "Error: Syntax Error: &lt;eof&gt;", "Error: Unknown error");
        assertFalse(html.contains("<eof>"));

        String escapedEntry = ChatHtmlTemplates.renderChatEntry("user<bad>", "<p>Safe</p>", "12:00<img src=x>", -1,
                false);
        assertContainsAll(escapedEntry, "user&lt;bad&gt;-entry", "12:00&lt;img src=x&gt;");
        assertFalse(escapedEntry.contains("<img src=x>"));
    }

    @Test
    void testMarkdownTableDecoratedWhileChatCardStructuralTablesPreserved() {
        MarkdownPane pane = new MarkdownPane();
        pane.appendMessage("ai", "| Col A | Col B |\n|---|---|\n| 1 | 2 |\n");
        assertContainsAll(pane.getText(), "md-table", "cellpadding", "cellspacing", "md-th", "md-td", "ai-entry",
                "ai-accent");
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
