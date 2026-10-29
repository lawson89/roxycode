package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Insets;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.*;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlServiceRegistry;

class SwingHtmlUtilsTest {

    @Test
    void testEscapeHtml() {
        assertEquals("", SwingHtmlUtils.escapeHtml(null));
        assertEquals("", SwingHtmlUtils.escapeHtml(""));
        assertEquals("&lt;div&gt;&quot;Hello &amp; World'&quot;&lt;/div&gt;",
                SwingHtmlUtils.escapeHtml("<div>" + '"' + "Hello & World'" + '"' + "</div>"));
    }

    @Test
    void testRenderMarkdownToHtml() {
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(null));
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(""));

        String html = SwingHtmlUtils.renderMarkdownToHtml(
                "# Heading\n\n| Name | Value |\n| :--- | ---: |\n| Key | 42 |\n\n~~~java\npublic class Demo {}\n~~~\n");
        assertContainsAll(html, "<h1>Heading</h1>", "md-table", "cellpadding", "md-th", "md-td",
                "<span class='tk-rw'>public</span>");
    }

    @Test
    void testStyleMarkdownTablesViaFlexmarkAttributeProviderDoesNotMutateCodeOrInlineHtml() {
        String rendered = SwingHtmlUtils
                .renderMarkdownToHtml("| Col A | Col B |\n| :--- | ---: |\n| Val 1 | Val 2 |\n\n"
                        + "<table><tr><td>Inline raw HTML table</td></tr></table>\n\n"
                        + "~~~html\n<table>\n  <tr><th>Header</th><td>Cell</td></tr>\n</table>\n~~~\n");
        assertContainsAll(rendered, "md-table", "md-th", "md-td", "right",
                "<table><tr><td>Inline raw HTML table</td></tr></table>", "&lt;table&gt;");
    }

    @Test
    void testApplyThemeAndReadOnlyCaretAndCursorConfiguration() {
        JEditorPane pane = SwingHtmlUtils.createHtmlEditorPane();
        SwingHtmlUtils.applyTheme(pane, 12);

        HTMLEditorKit kit = assertInstanceOf(HTMLEditorKit.class, pane.getEditorKit());
        assertEquals(Cursor.DEFAULT_CURSOR, kit.getDefaultCursor().getType());
        assertEquals(Cursor.HAND_CURSOR, kit.getLinkCursor().getType());
        assertEquals(Cursor.DEFAULT_CURSOR, pane.getCursor().getType());
        assertNotNull(pane.getBackground());
        assertNotNull(pane.getForeground());
        assertTrue(pane.isOpaque());

        assertInstanceOf(SwingHtmlUtils.NonPaintingCaret.class, pane.getCaret());
        assertEquals(0, pane.getCaret().getBlinkRate());
        assertEquals(0, pane.getCaretColor().getAlpha());

        pane.setText("<html><body>Hello Selectable World</body></html>");
        pane.selectAll();
        assertNotNull(pane.getSelectedText());
        assertTrue(pane.getSelectedText().contains("Hello Selectable World"));
    }

    @Test
    void testWrappingEditorKitViewsAndDocumentIntegrity() throws Exception {
        JTextPane pane = new JTextPane();
        pane.setContentType("text/html");
        SwingHtmlUtils.applyTheme(pane, 10);

        String longCodeLine = "public static void veryLongMethodNameWithManyParameters(String paramOne, String paramTwo, String paramThree) {}";
        String longInlineToken = "https://example.com/very/long/path/that/has/no/spaces/and/should/allow/horizontal/wrapping/in/view";
        pane.setText("<html><body><table><tr><td nowrap='nowrap'>0.</td><td><div>" + longInlineToken
                + "<br>SecondLine</div></td></tr></table><pre>" + longCodeLine + "</pre></body></html>");

        ViewFactory factory = ((HTMLEditorKit) pane.getEditorKit()).getViewFactory();
        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        assertContainsAll(doc.getText(0, doc.getLength()), longCodeLine, longInlineToken);
        assertNotNull(SwingTestUtils.findElement(doc.getDefaultRootElement(), "br", e -> true));

        Element nowrapTd = SwingTestUtils.findElement(doc.getDefaultRootElement(), "td",
                e -> e.getAttributes().isDefined(HTML.Attribute.NOWRAP));
        assertNotNull(nowrapTd);
        assertTrue(hasPositiveInlineMinSpan(nowrapTd, factory));
        assertTrue(verifyWrappingViews(doc.getDefaultRootElement(), nowrapTd, factory));
    }

    private boolean hasPositiveInlineMinSpan(Element elem, ViewFactory factory) {
        if (factory.create(elem) instanceof InlineView iv && iv.getMinimumSpan(View.X_AXIS) > 0f) {
            return true;
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            if (hasPositiveInlineMinSpan(elem.getElement(i), factory)) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyWrappingViews(Element elem, Element excludedSubtree, ViewFactory factory) {
        if (elem == excludedSubtree) {
            return false;
        }
        boolean foundBr = false;
        View view = factory.create(elem);
        if ("br".equalsIgnoreCase(elem.getName())) {
            foundBr = true;
            assertEquals(View.ForcedBreakWeight, view.getBreakWeight(View.X_AXIS, 0f, 100f));
        } else if (view instanceof InlineView || view instanceof ParagraphView
                || (view instanceof BlockView && !"body".equalsIgnoreCase(elem.getName()))) {
            assertEquals(0f, view.getMinimumSpan(View.X_AXIS));
            if ("p-implied".equalsIgnoreCase(elem.getName())) {
                assertEquals(ParagraphView.class, view.getClass().getSuperclass());
            }
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            foundBr |= verifyWrappingViews(elem.getElement(i), excludedSubtree, factory);
        }
        return foundBr;
    }

    @Test
    void testGetUiColorAndToHexAndJexlApiPanelCopyFeedback() {
        Color fallback = new Color(0x12, 0x34, 0x56);
        assertEquals("#123456", SwingHtmlUtils.toHex(fallback));
        assertEquals(fallback, SwingHtmlUtils.getUiColor(fallback, "NonExistent.uiKey.12345"));

        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlApiPanel apiPanel = new JexlApiPanel(registry);
        assertEquals(JexlApiPanel.COPY_BUTTON_TEXT, apiPanel.getCopyButton().getText());
        assertEquals(0, apiPanel.getTextPane().getSelectionStart());
        assertEquals(0, apiPanel.getTextPane().getSelectionEnd());

        apiPanel.getCopyButton().doClick();
        assertEquals(JexlApiPanel.COPIED_BUTTON_TEXT, apiPanel.getCopyButton().getText());
        assertEquals(registry.getDocumentation(null), apiPanel.getLastCopiedDocumentation());
        assertEquals(0, apiPanel.getTextPane().getSelectionEnd() - apiPanel.getTextPane().getSelectionStart());

        JButton btn = new JButton("Original");
        Timer firstTimer = SwingHtmlUtils.showCopyButtonFeedback(btn, null, "Copied!", null, "Original", null, 5000);
        assertEquals("Copied!", btn.getText());
        assertTrue(firstTimer.isRunning());
        assertFalse(firstTimer.isRepeats());

        Timer secondTimer = SwingHtmlUtils.showCopyButtonFeedback(btn, firstTimer, "Copied Again!", null, "Original",
                null, 5000);
        assertFalse(firstTimer.isRunning());
        assertTrue(secondTimer.isRunning());
        assertEquals("Copied Again!", btn.getText());
        for (var listener : secondTimer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        secondTimer.stop();
        assertEquals("Original", btn.getText());

        Border border = SwingHtmlUtils.createThemeMatteBorder(1, 0, 1, 0);
        assertEquals(new Insets(1, 0, 1, 0), border.getBorderInsets(btn));
    }

}
