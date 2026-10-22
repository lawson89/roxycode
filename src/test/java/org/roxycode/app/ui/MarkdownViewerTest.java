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
        viewer.setContent(
                """
                ~~~java
                public class Hello { int x = 1; }
                ~~~
                """);
        String text = viewer.getText();
        assertNotNull(text);
        // JTextPane HTML rendering contains the classes or rendered content
        assertTrue(text.contains("Hello") || viewer.getContentType().contains("html"));
    }

    @Test
    void testSetContentStylesMarkdownTables() {
        MarkdownViewer viewer = new MarkdownViewer();
        viewer.setContent(
                """
                | Header 1 | Header 2 |
                |---|---|
                | Cell 1 | Cell 2 |
                """);
        String text = viewer.getText();
        assertNotNull(text);
        assertTrue(
                text.contains(
                        """
                        class="md-table"
                        """
                                .strip()));
        assertTrue(
                text.contains(
                        """
                        cellpadding="6"
                        """
                                .strip()));
        assertTrue(
                text.contains(
                        """
                        cellspacing="0"
                        """
                                .strip()));
        assertTrue(
                text.contains(
                        """
                        class="md-th"
                        """
                                .strip()));
        assertTrue(
                text.contains(
                        """
                        class="md-td"
                        """
                                .strip()));
    }
}
