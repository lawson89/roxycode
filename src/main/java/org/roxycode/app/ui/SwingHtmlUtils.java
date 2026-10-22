package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatLaf;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.BlockView;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import javax.swing.text.html.ParagraphView;
import javax.swing.text.html.StyleSheet;

/**
 * Shared utility for Swing HTML 3.2 and CSS 1.0 styling, theming, and common editor component
 * behaviors.
 */
public final class SwingHtmlUtils {

    private static final DataHolder FLEXMARK_OPTIONS =
            new MutableDataSet()
                    .set(
                            Parser.EXTENSIONS,
                            Arrays.asList(
                                    TablesExtension.create(), StrikethroughExtension.create()))
                    .toImmutable();
    private static final Parser MARKDOWN_PARSER = Parser.builder(FLEXMARK_OPTIONS).build();
    private static final HtmlRenderer MARKDOWN_RENDERER =
            HtmlRenderer.builder(FLEXMARK_OPTIONS).build();

    private SwingHtmlUtils() {}

    /** Returns the shared Flexmark {@link Parser} configured with GFM tables and strikethrough. */
    public static Parser markdownParser() {
        return MARKDOWN_PARSER;
    }

    /**
     * Returns the shared Flexmark {@link HtmlRenderer} configured with GFM tables and
     * strikethrough.
     */
    public static HtmlRenderer markdownRenderer() {
        return MARKDOWN_RENDERER;
    }

    /**
     * Renders Markdown into Swing-compatible HTML with GFM table styling and code block syntax
     * highlighting applied.
     *
     * @param markdown the raw Markdown string
     * @return the rendered and styled HTML fragment
     */
    public static String renderMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String html =
                styleMarkdownTables(MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown)));
        return JexlToHtmlConverter.highlightCodeBlocks(html);
    }

    /**
     * Decorates Flexmark-rendered Markdown table tags ({@code <table>}, {@code <th>}, {@code <td>})
     * with Swing HTML 3.2 / CSS 1.0 classes and attributes without affecting structural layout
     * tables.
     *
     * @param html the Flexmark-rendered HTML fragment
     * @return the HTML fragment with Markdown table classes and attributes applied
     */
    public static String styleMarkdownTables(String html) {
        if (html == null || html.isEmpty() || !html.contains("<table")) {
            return html == null ? "" : html;
        }
        return html.replace(
                        "<table>",
                        """
                        <table class="md-table" cellpadding="6" cellspacing="0" border="0">"""
                                .strip())
                .replace(
                        "<th>",
                        """
                        <th class="md-th">"""
                                .strip())
                .replace(
                        "<th align=",
                        """
                        <th class="md-th" align="""
                                .strip())
                .replace(
                        "<td>",
                        """
                        <td class="md-td">"""
                                .strip())
                .replace(
                        "<td align=",
                        """
                        <td class="md-td" align="""
                                .strip());
    }

    /**
     * Applies theme-aware stylesheet and editor kit to a JEditorPane or JTextPane.
     *
     * @param textPane the text pane to style
     * @param marginPx the body margin in pixels
     */
    public static void applyTheme(JEditorPane textPane, int marginPx) {
        HTMLEditorKit kit =
                new HTMLEditorKit() {
                    private static final long serialVersionUID = 1L;
                    private final transient ViewFactory factory = new WrappingHtmlFactory();

                    @Override
                    public ViewFactory getViewFactory() {
                        return factory;
                    }
                };
        StyleSheet sheet = new StyleSheet();

        boolean isDark = FlatLaf.isLafDark();
        String cssPath = isDark ? "/css/chat-styles-dark.css" : "/css/chat-styles-light.css";
        URL cssUrl = SwingHtmlUtils.class.getResource(cssPath);
        if (cssUrl != null) {
            sheet.importStyleSheet(cssUrl);
        }

        Color bg = UIManager.getColor("TextPane.background");
        Color fg = UIManager.getColor("TextPane.foreground");
        if (bg == null) bg = isDark ? new Color(30, 30, 30) : Color.WHITE;
        if (fg == null) fg = isDark ? Color.LIGHT_GRAY : Color.BLACK;

        textPane.setBackground(bg);
        textPane.setForeground(fg);
        textPane.setOpaque(true);

        String bodyRule =
                String.format(
                        "body { background-color: #%02x%02x%02x; color: #%02x%02x%02x; margin: %dpx; }",
                        bg.getRed(),
                        bg.getGreen(),
                        bg.getBlue(),
                        fg.getRed(),
                        fg.getGreen(),
                        fg.getBlue(),
                        marginPx);
        sheet.addRule(bodyRule);

        kit.setStyleSheet(sheet);
        textPane.setEditorKit(kit);
    }

    private static boolean isNoWrap(Element elem) {
        for (Element curr = elem; curr != null; curr = curr.getParentElement()) {
            AttributeSet attrs = curr.getAttributes();
            if (attrs != null) {
                if (attrs.isDefined(HTML.Attribute.NOWRAP)) {
                    return true;
                }
                Object ws = attrs.getAttribute(CSS.Attribute.WHITE_SPACE);
                if (ws != null && "nowrap".equalsIgnoreCase(ws.toString())) {
                    return true;
                }
            }
            if ("td".equalsIgnoreCase(curr.getName()) || "th".equalsIgnoreCase(curr.getName())) {
                break;
            }
        }
        return false;
    }

    private static final class WrappingHtmlFactory extends HTMLEditorKit.HTMLFactory {
        @Override
        public View create(Element elem) {
            View view = super.create(elem);
            if (isNoWrap(elem)) {
                return view;
            }
            if (view instanceof InlineView) {
                return new InlineView(elem) {
                    @Override
                    public float getMinimumSpan(int axis) {
                        return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                    }
                };
            }
            if (view instanceof ParagraphView) {
                return new ParagraphView(elem) {
                    @Override
                    public float getMinimumSpan(int axis) {
                        return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                    }
                };
            }
            if (view instanceof BlockView blockView && !"body".equalsIgnoreCase(elem.getName())) {
                return new BlockView(elem, blockView.getAxis()) {
                    @Override
                    public float getMinimumSpan(int axis) {
                        return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                    }
                };
            }
            return view;
        }
    }

    /**
     * Installs a standard context menu (Copy, Select All) on a text component.
     *
     * @param component the text component to attach the menu to
     */
    public static void installTextContextMenu(JTextComponent component) {
        component.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (e.isPopupTrigger()) showPopup(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        if (e.isPopupTrigger()) showPopup(e);
                    }

                    private void showPopup(MouseEvent e) {
                        component.requestFocusInWindow();
                        JPopupMenu menu = new JPopupMenu();
                        JMenuItem copy = new JMenuItem(new DefaultEditorKit.CopyAction());
                        copy.setText("Copy");
                        menu.add(copy);
                        menu.addSeparator();
                        JMenuItem selectAll = new JMenuItem("Select All");
                        selectAll.addActionListener(ae -> component.selectAll());
                        menu.add(selectAll);
                        menu.show(e.getComponent(), e.getX(), e.getY());
                    }
                });
    }

    /**
     * Escapes text for safe inclusion in HTML bodies.
     *
     * @param text the raw text
     * @return HTML entity escaped text
     */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(
                        """
                        "
                        """
                                .strip(),
                        "&quot;")
                .replace("'", "&#39;");
    }
}
