package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.*;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.BlockView;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import javax.swing.text.html.ParagraphView;
import org.junit.jupiter.api.Test;

class SwingHtmlUtilsTest {

    @Test
    void testEscapeHtml() {
        assertEquals("", SwingHtmlUtils.escapeHtml(null));
        assertEquals("", SwingHtmlUtils.escapeHtml(""));
        assertEquals(
                "&lt;div&gt;&quot;Hello &amp; World&#39;&quot;&lt;/div&gt;",
                SwingHtmlUtils.escapeHtml(
                        """
                        <div>"Hello & World'"</div>"""
                                .strip()));
    }

    @Test
    void testRenderMarkdownToHtml() {
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(null));
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(""));

        String markdown =
                """
                # Heading

                | Name | Value |
                | :--- | ---: |
                | Key | 42 |

                ~~~java
                public class Demo {}
                ~~~
                """;
        String html = SwingHtmlUtils.renderMarkdownToHtml(markdown);

        assertTrue(html.contains("<h1>Heading</h1>"));
        assertTrue(
                html.contains(
                        """
                        <table class="md-table" cellpadding="6" cellspacing="0" border="0">"""
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        class="md-th"
                        """
                                .strip()));
        assertTrue(
                html.contains(
                        """
                        align="right" class="md-td"
                        """
                                .strip()));
        assertTrue(html.contains("<span class='tk-rw'>public</span>"));
    }

    @Test
    void testStyleMarkdownTablesViaFlexmarkAttributeProviderDoesNotMutateCodeOrInlineHtml() {
        String markdown =
                """
                | Col A | Col B |
                | :--- | ---: |
                | Val 1 | Val 2 |

                <table><tr><td>Inline raw HTML table</td></tr></table>

                ~~~html
                <table>
                  <tr><th>Header</th><td>Cell</td></tr>
                </table>
                ~~~
                """;
        String rendered = SwingHtmlUtils.renderMarkdownToHtml(markdown);

        assertTrue(
                rendered.contains(
                        """
                        <table class="md-table" cellpadding="6" cellspacing="0" border="0">"""
                                .strip()),
                "Markdown TableBlock AST node should receive md-table attributes");
        assertTrue(
                rendered.contains(
                        """
                        class="md-th"
                        """
                                .strip()));
        assertTrue(
                rendered.contains(
                        """
                        class="md-td"
                        """
                                .strip()));
        assertTrue(
                rendered.contains(
                        """
                        align="right"
                        """
                                .strip()));
        assertTrue(
                rendered.contains("<table><tr><td>Inline raw HTML table</td></tr></table>"),
                "Raw inline HTML tables should not be mutated by string replacement");
        assertTrue(
                rendered.contains("&lt;table&gt;"),
                "Escaped HTML table tags inside fenced code blocks must remain untouched");
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
    void testWrappingEditorKitViewsAndDocumentIntegrity() throws Exception {
        JTextPane pane = new JTextPane();
        pane.setContentType("text/html");
        SwingHtmlUtils.applyTheme(pane, 10);

        String longCodeLine =
                "public static void veryLongMethodNameWithManyParameters(String paramOne, String paramTwo, String paramThree) {}";
        String longInlineToken =
                "https://example.com/very/long/path/that/has/no/spaces/and/should/allow/horizontal/wrapping/in/view";
        pane.setText(
                "<html><body><div>"
                        + longInlineToken
                        + "</div><pre>"
                        + longCodeLine
                        + "</pre></body></html>");

        HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
        ViewFactory factory = kit.getViewFactory();
        assertNotNull(factory);

        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        String extractedText = doc.getText(0, doc.getLength());
        assertTrue(
                extractedText.contains(longCodeLine),
                "Underlying document text for <pre> should remain intact without injected characters");
        assertTrue(
                extractedText.contains(longInlineToken),
                "Underlying document text for inline content should remain intact");

        verifyWrappingViews(doc.getDefaultRootElement(), factory);
    }

    private void verifyWrappingViews(Element elem, ViewFactory factory) {
        View view = factory.create(elem);
        if (view instanceof InlineView
                || view instanceof ParagraphView
                || (view instanceof BlockView && !"body".equalsIgnoreCase(elem.getName()))) {
            assertEquals(
                    0f,
                    view.getMinimumSpan(View.X_AXIS),
                    "X_AXIS minimum span should be 0 for "
                            + view.getClass().getSuperclass().getSimpleName());
            if ("p-implied".equalsIgnoreCase(elem.getName())) {
                assertEquals(
                        ParagraphView.class,
                        view.getClass().getSuperclass(),
                        "<pre> p-implied element should use ParagraphView rather than LineView");
            }
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            verifyWrappingViews(elem.getElement(i), factory);
        }
    }

    @Test
    void testInstallTextContextMenu() {
        JTextArea area = new JTextArea("Sample text");
        int initialListeners = area.getMouseListeners().length;

        SwingHtmlUtils.installTextContextMenu(area);

        assertEquals(initialListeners + 1, area.getMouseListeners().length);
    }
}
