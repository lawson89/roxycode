package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.event.HyperlinkEvent;
import org.junit.jupiter.api.Test;

class MarkdownPaneTest {

  @Test
  void testCopyHyperlinkCopiesRawMarkdownAndShowsFeedback() {
    MarkdownPane pane = new MarkdownPane();
    String userPrompt = "How do I print in Java?";
    String aiMarkdown =
        "Here is **bold** markdown and code:\n```java\nSystem.out.println(42);\n```";

    pane.appendMessage("user", userPrompt);
    pane.appendMessage("ai", aiMarkdown);

    String initialHtml = pane.getText();
    assertTrue(initialHtml.contains("copy:1"), "AI message at index 1 should have copy:1 link");
    assertFalse(
        initialHtml.contains("copy:0"), "User message at index 0 should not have copy link");
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
}
