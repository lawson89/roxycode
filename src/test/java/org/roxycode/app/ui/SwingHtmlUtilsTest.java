package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;

import static org.junit.jupiter.api.Assertions.*;

class SwingHtmlUtilsTest {

    @Test
    void testEscapeHtml() {
        assertEquals("", SwingHtmlUtils.escapeHtml(null));
        assertEquals("", SwingHtmlUtils.escapeHtml(""));
        assertEquals("&lt;div&gt;&quot;Hello &amp; World&#39;&quot;&lt;/div&gt;",
                SwingHtmlUtils.escapeHtml("<div>\"Hello & World'\"</div>"));
    }

    @Test
    void testApplyTheme() {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");

        SwingHtmlUtils.applyTheme(pane, 12);

        assertTrue(pane.getEditorKit() instanceof HTMLEditorKit);
        assertNotNull(pane.getBackground());
        assertNotNull(pane.getForeground());
        assertTrue(pane.isOpaque());
    }

    @Test
    void testInstallTextContextMenu() {
        JTextArea area = new JTextArea("Sample text");
        int initialListeners = area.getMouseListeners().length;

        SwingHtmlUtils.installTextContextMenu(area);

        assertEquals(initialListeners + 1, area.getMouseListeners().length);
    }
}
