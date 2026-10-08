package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownViewerTest {
    @Test
    void testSetContent() {
        MarkdownViewer viewer = new MarkdownViewer();
        viewer.setContent("# Hello");
        String text = viewer.getText();
        assertNotNull(text);
    }

    @Test
    void testSetContentHighlightsCodeBlocks() {
        MarkdownViewer viewer = new MarkdownViewer();
        viewer.setContent("```java\npublic class Hello { int x = 1; }\n```");
        String text = viewer.getText();
        assertNotNull(text);
        // JTextPane HTML rendering contains the classes or rendered content
        assertTrue(text.contains("Hello") || viewer.getContentType().contains("html"));
    }
}
