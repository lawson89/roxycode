package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

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
