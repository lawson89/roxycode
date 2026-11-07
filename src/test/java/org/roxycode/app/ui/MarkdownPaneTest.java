package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.ChatTranscriptEntry;

class MarkdownPaneTest {

    @Test
    void testMoveToFeatureLink() {
        MarkdownPane pane = new MarkdownPane(false);
        String[] movedContent = new String[1];
        pane.setOnMoveToFeatureListener(content -> movedContent[0] = content);

        pane.appendMessage("ai", "Feature suggestion");
        String text = pane.getText();
        assertTrue(text.contains("feature:0"));
        assertTrue(text.contains("[Move to Feature") && text.contains("Request]"));

        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "feature:0"));
        assertEquals("Feature suggestion", movedContent[0]);

        MarkdownPane taskPane = new MarkdownPane(true);
        taskPane.appendMessage("ai", "Task response");
        assertFalse(taskPane.getText().contains("feature:0"));
    }

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
        pane.appendMessage("ai", String.join(System.lineSeparator(), "| Col A | Col B |", "|---|---|", "| 1 | 2 |"));

        String html = pane.getText();
        assertContainsAll(html, "Executed successfully", "Error: Syntax Error: &lt;eof&gt;", "md-table", "copy-cell-3",
                "copy-cell-5", "tool-accent", "tool-accent-error");
        assertFalse(html.contains("<eof>"));

        List<ChatTranscriptEntry> transcript = pane.getConversationMessages();
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
        assertContainsAll(pane.getText(), "Copied!", "feature:3", "feature:5", "copy:5");

        pane.copyMessageMarkdown(5);
        assertEquals(5, pane.copiedMessageIndex);
        assertContainsAll(pane.getText(), "copy:3", "feature:3", "feature:5");

        for (ActionListener listener : pane.copyFeedbackTimer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        pane.copyFeedbackTimer.stop();
        assertEquals(-1, pane.copiedMessageIndex);
        assertContainsAll(pane.getText(), "copy:3", "copy:5", "feature:3", "feature:5");

        pane.clear();
        assertTrue(pane.getConversationMessages().isEmpty());
    }

    @Test
    void testCollapsibleToolCardsAndSemanticSummary() {
        String multiLineScript = String.join(System.lineSeparator(),
                "var content = fileReadService.readLines('src/App.java', 1, 20);",
                "fileEditorService.replaceBlock('src/App.java', 'old', 'new');", "planService.markStepCompleted(0);",
                "var build = buildToolService.buildAndTest();", "build.success;");
        String summary = ChatHtmlTemplates.summarizeToolScript(JexlToHtmlConverter.convert(multiLineScript));
        assertContainsAll(summary, "fileReadService.readLines", "fileEditorService.replaceBlock",
                "planService.markStepCompleted", "(+1 more)", "(5 lines)");

        String plainFallbackSummary = ChatHtmlTemplates
                .summarizeToolScript(String.join(System.lineSeparator(), "// comment", "var answer = 42;", "answer;"));
        assertEquals("var answer = 42; (3 lines)", plainFallbackSummary);

        MarkdownPane pane = new MarkdownPane(true);
        pane.appendToolLog(multiLineScript, true, null);
        String collapsedHtml = pane.getText();
        assertContainsAll(collapsedHtml, "toggle-tool:0", "[Show 5 lines]", "fileReadService.readLines",
                "fileEditorService.replaceBlock", "(5 lines)");
        assertFalse(collapsedHtml.contains("build.success;"));

        HTMLDocument htmlDoc = (HTMLDocument) pane.getDocument();
        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "toggle-tool:0"));
        assertSame(htmlDoc, pane.getDocument());
        String expandedHtml = pane.getText();
        assertContainsAll(expandedHtml, "toggle-tool:0", "[Hide code]", "build.success;");

        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "toggle-tool:0"));
        assertContainsAll(pane.getText(), "[Show 5 lines]", "fileReadService.readLines");
        assertFalse(pane.getText().contains("build.success;"));

        String failedMultiLine = String.join(System.lineSeparator(), "line1", "line2", "line3", "line4",
                "line5_failed");
        pane.appendToolLog(failedMultiLine, false, "Compilation failed");
        assertContainsAll(pane.getText(), "tool-call-error", "tool-accent-error", "[Hide code]", "line5_failed",
                "Compilation failed");

        pane.appendMessage("user", "Verify persistence");
        pane.appendMessage("ai", "Persistence verified.");
        List<MarkdownPane.PersistedChatEntry> exported = pane.exportMessages();
        assertEquals(4, exported.size());
        assertTrue(exported.get(0).isTool());
        assertFalse(exported.get(0).toolExpanded());
        assertTrue(exported.get(1).isTool());
        assertTrue(exported.get(1).toolExpanded());
        assertEquals("Compilation failed", exported.get(1).error());

        MarkdownPane restoredPane = new MarkdownPane(true);
        restoredPane.restoreMessages(exported);
        assertEquals(exported, restoredPane.exportMessages());
        assertContainsAll(restoredPane.getText(), "[Show 5 lines]", "line5_failed", "Compilation failed",
                "Verify persistence", "Persistence verified.");
    }
}
