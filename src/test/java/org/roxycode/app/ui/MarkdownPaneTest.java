package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.awt.event.ActionListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import org.junit.jupiter.api.Test;

class MarkdownPaneTest {

    @Test
    void testIncrementalAppendInPlaceCopyFeedbackToolLogsAndTranscript() {
        MarkdownPane pane = new MarkdownPane(false);
        assertContainsAll(pane.getText(), "welcome-card", "Technical Mentor (Read-Only Explore Mode)", "@ Mention");
        assertTrue(new MarkdownPane(true).getText().contains("Autonomous Feature Development"));

        pane.appendMessage("user", "Prompt 1");
        Document doc = pane.getDocument();
        pane.appendToolLog("var x = 1;", true, null);
        pane.appendToolLog("if (", false, "Syntax Error: <eof>");
        pane.appendMessage("ai", "AI Response 1");
        pane.appendMessage("user", "Prompt 2");
        pane.appendMessage("ai", "| Col A | Col B |\n|---|---|\n| 1 | 2 |\n");
        pane.appendMessage("system", "Notice");
        assertSame(doc, pane.getDocument());

        String html = pane.getText();
        assertContainsAll(html, "Executed successfully", "Error: Syntax Error: &lt;eof&gt;", "md-table", "copy-cell-3",
                "copy-cell-5");
        assertFalse(html.contains("<eof>"));

        var transcript = pane.getConversationMessages();
        assertEquals(4, transcript.size());
        assertEquals("user", transcript.get(0).role());
        assertEquals("ai", transcript.get(1).role());

        HTMLDocument htmlDoc = (HTMLDocument) pane.getDocument();
        assertNotNull(htmlDoc.getElement("copy-cell-3"));
        pane.setCaretPosition(5);

        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "copy:3"));
        assertSame(htmlDoc, pane.getDocument());
        assertEquals(3, pane.copiedMessageIndex);
        assertEquals("AI Response 1", pane.lastCopiedMarkdown);
        assertEquals(5, pane.getCaretPosition());
        assertContainsAll(pane.getText(), "Copied!", "copy:5");

        pane.copyMessageMarkdown(5);
        assertEquals(5, pane.copiedMessageIndex);
        assertTrue(pane.getText().contains("copy:3"));

        for (ActionListener listener : pane.copyFeedbackTimer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        pane.copyFeedbackTimer.stop();
        assertEquals(-1, pane.copiedMessageIndex);

        AtomicInteger spokenIndex = new AtomicInteger(-1);
        AtomicReference<String> spokenMarkdown = new AtomicReference<>();
        pane.setSpeakActionHandler((idx, md) -> {
            spokenIndex.set(idx);
            spokenMarkdown.set(md);
        });
        assertTrue(pane.getText().contains("speak:3"));
        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "speak:3"));
        assertEquals(3, spokenIndex.get());
        assertEquals("AI Response 1", spokenMarkdown.get());

        pane.setSpeakingMessageIndex(3);
        assertEquals(3, pane.getSpeakingMessageIndex());
        assertTrue(pane.getText().contains("[Stop]"));
        pane.setSpeakingMessageIndex(-1);
        assertEquals(-1, pane.getSpeakingMessageIndex());
        assertFalse(pane.getText().contains("[Stop]"));

        pane.clear();
        assertTrue(pane.getConversationMessages().isEmpty());
    }
}
