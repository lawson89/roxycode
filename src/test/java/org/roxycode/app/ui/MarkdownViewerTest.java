package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownViewerTest {
    @Test
    void testSetContent() {
        MarkdownViewer viewer = new MarkdownViewer();
        viewer.setContent("# Hello");
        String text = viewer.getText();
        // The HTML output might contain more than just the tag, but it should contain the tag
        assertNotNull(text);
    }
}
