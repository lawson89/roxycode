package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.awt.Color;
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
        assertEquals(
                "&lt;div&gt;&quot;Hello &amp; World'&quot;&lt;/div&gt;",
                SwingHtmlUtils.escapeHtml("<div>" + '"' + "Hello & World'" + '"' + "</div>"));
    }

    @Test
    void testRenderMarkdownToHtml() {
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(null));
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(""));

        String html =
                SwingHtmlUtils.renderMarkdownToHtml(
                        "# Heading\n\n| Name | Value |\n| :--- | ---: |\n| Key | 42 |\n\n~~~java\npublic class Demo {}\n~~~\n");
        assertContainsAll(
                html,
                "<h1>Heading</h1>",
                "md-table",
                "cellpadding",
                "md-th",
                "md-td",
                "<span class='tk-rw'>public</span>");
    }

    @Test
    void testStyleMarkdownTablesViaFlexmarkAttributeProviderDoesNotMutateCodeOrInlineHtml() {
        String rendered =
                SwingHtmlUtils.renderMarkdownToHtml(
                        "| Col A | Col B |\n| :--- | ---: |\n| Val 1 | Val 2 |\n\n"
                                + "<table><tr><td>Inline raw HTML table</td></tr></table>\n\n"
                                + "~~~html\n<table>\n  <tr><th>Header</th><td>Cell</td></tr>\n</table>\n~~~\n");
        assertContainsAll(
                rendered,
                "md-table",
                "md-th",
                "md-td",
                "right",
                "<table><tr><td>Inline raw HTML table</td></tr></table>",
                "&lt;table&gt;");
    }

    @Test
    void testApplyTheme() {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        SwingHtmlUtils.applyTheme(pane, 12);

        assertInstanceOf(HTMLEditorKit.class, pane.getEditorKit());
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
                        + "<br>SecondLine</div><pre>"
                        + longCodeLine
                        + "</pre></body></html>");

        ViewFactory factory = ((HTMLEditorKit) pane.getEditorKit()).getViewFactory();
        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        assertContainsAll(doc.getText(0, doc.getLength()), longCodeLine, longInlineToken);
        assertNotNull(SwingTestUtils.findElement(doc.getDefaultRootElement(), "br", e -> true));
        assertTrue(verifyWrappingViews(doc.getDefaultRootElement(), factory));
    }

    private boolean verifyWrappingViews(Element elem, ViewFactory factory) {
        boolean foundBr = false;
        View view = factory.create(elem);
        if ("br".equalsIgnoreCase(elem.getName())) {
            foundBr = true;
            assertEquals(View.ForcedBreakWeight, view.getBreakWeight(View.X_AXIS, 0f, 100f));
        } else if (view instanceof InlineView
                || view instanceof ParagraphView
                || (view instanceof BlockView && !"body".equalsIgnoreCase(elem.getName()))) {
            assertEquals(0f, view.getMinimumSpan(View.X_AXIS));
            if ("p-implied".equalsIgnoreCase(elem.getName())) {
                assertEquals(ParagraphView.class, view.getClass().getSuperclass());
            }
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            foundBr |= verifyWrappingViews(elem.getElement(i), factory);
        }
        return foundBr;
    }

    @Test
    void testInstallTextContextMenuAndCopyToClipboard() {
        JTextArea area = new JTextArea("Sample text");
        int initialListeners = area.getMouseListeners().length;
        SwingHtmlUtils.installTextContextMenu(area);

        assertEquals(initialListeners + 1, area.getMouseListeners().length);
        assertDoesNotThrow(() -> SwingHtmlUtils.copyToClipboard("Copied text"));
        assertDoesNotThrow(() -> SwingHtmlUtils.copyToClipboard(null));
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
        assertEquals(
                0,
                apiPanel.getTextPane().getSelectionEnd()
                        - apiPanel.getTextPane().getSelectionStart());

        JButton btn = new JButton("Original");
        Timer firstTimer =
                SwingHtmlUtils.showCopyButtonFeedback(
                        btn, null, "Copied!", null, "Original", null, 5000);
        assertEquals("Copied!", btn.getText());
        assertTrue(firstTimer.isRunning());
        assertFalse(firstTimer.isRepeats());

        Timer secondTimer =
                SwingHtmlUtils.showCopyButtonFeedback(
                        btn, firstTimer, "Copied Again!", null, "Original", null, 5000);
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

    @Test
    void testGetAccentColorAndGetMutedForegroundColor() {
        UIDefaults defaults = UIManager.getDefaults();
        Object origAccent = defaults.get("Component.accentColor");
        Object origActionsBlue = defaults.get("Actions.Blue");
        Object origDisabledFg = defaults.get("Label.disabledForeground");
        Object origInfoFg = defaults.get("Component.infoForeground");
        try {
            Color customAccent = new Color(0x11, 0x22, 0x33);
            Color customBlue = new Color(0x44, 0x55, 0x66);
            Color customDisabled = new Color(0x77, 0x88, 0x99);
            Color customInfo = new Color(0xaa, 0xbb, 0xcc);

            defaults.put("Component.accentColor", customAccent);
            defaults.put("Actions.Blue", customBlue);
            assertEquals(customAccent, SwingHtmlUtils.getAccentColor());

            defaults.put("Component.accentColor", "not-a-color");
            assertEquals(customBlue, SwingHtmlUtils.getAccentColor());

            defaults.put("Actions.Blue", "not-a-color");
            assertEquals(new Color(0x00, 0x7a, 0xcc), SwingHtmlUtils.getAccentColor());

            defaults.put("Label.disabledForeground", customDisabled);
            defaults.put("Component.infoForeground", customInfo);
            assertEquals(customDisabled, SwingHtmlUtils.getMutedForegroundColor());

            defaults.put("Label.disabledForeground", "not-a-color");
            assertEquals(customInfo, SwingHtmlUtils.getMutedForegroundColor());

            defaults.put("Component.infoForeground", "not-a-color");
            assertEquals(Color.GRAY, SwingHtmlUtils.getMutedForegroundColor());
        } finally {
            defaults.put("Component.accentColor", origAccent);
            defaults.put("Actions.Blue", origActionsBlue);
            defaults.put("Label.disabledForeground", origDisabledFg);
            defaults.put("Component.infoForeground", origInfoFg);
        }
    }

    @Test
    void testBlendColorAndGetTintedBackground() {
        Color fg = new Color(100, 200, 50);
        Color bg = new Color(200, 100, 150);

        assertEquals(bg, SwingHtmlUtils.blendColor(fg, bg, 0.0));
        assertEquals(bg, SwingHtmlUtils.blendColor(fg, bg, -0.5));
        assertEquals(fg, SwingHtmlUtils.blendColor(fg, bg, 1.0));
        assertEquals(fg, SwingHtmlUtils.blendColor(fg, bg, 1.5));

        Color half = SwingHtmlUtils.blendColor(fg, bg, 0.5);
        assertEquals(new Color(150, 150, 100), half);
        assertEquals(255, half.getAlpha());

        UIDefaults defaults = UIManager.getDefaults();
        Object origPanelBg = defaults.get("Panel.background");
        try {
            defaults.put("Panel.background", new Color(250, 250, 250));
            Color tinted = SwingHtmlUtils.getTintedBackground(new Color(0, 100, 200), 0.10);
            assertEquals(new Color(225, 235, 245), tinted);
            assertEquals(255, tinted.getAlpha());
        } finally {
            defaults.put("Panel.background", origPanelBg);
        }
    }
}
