package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatLaf;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.html.MutableAttributes;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
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
import org.apache.commons.text.StringEscapeUtils;

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
                                    TablesExtension.create(),
                                    StrikethroughExtension.create(),
                                    new MarkdownTableAttributeExtension()))
                    .toImmutable();
    private static final Parser MARKDOWN_PARSER = Parser.builder(FLEXMARK_OPTIONS).build();
    private static final HtmlRenderer MARKDOWN_RENDERER =
            HtmlRenderer.builder(FLEXMARK_OPTIONS).build();

    private SwingHtmlUtils() {}

    /**
     * Flexmark {@link HtmlRenderer.HtmlRendererExtension} that decorates AST {@link TableBlock} and
     * {@link TableCell} nodes with Swing HTML 3.2 / CSS 1.0 classes and attributes directly during
     * rendering without post-hoc string replacement.
     */
    private static final class MarkdownTableAttributeExtension
            implements HtmlRenderer.HtmlRendererExtension {
        @Override
        public void rendererOptions(MutableDataHolder options) {}

        @Override
        public void extend(HtmlRenderer.Builder htmlRendererBuilder, String rendererType) {
            htmlRendererBuilder.attributeProviderFactory(
                    new IndependentAttributeProviderFactory() {
                        @Override
                        public AttributeProvider apply(LinkResolverContext context) {
                            return (node, part, attributes) ->
                                    setTableNodeAttributes(node, attributes);
                        }
                    });
        }
    }

    private static void setTableNodeAttributes(Node node, MutableAttributes attributes) {
        if (node instanceof TableBlock) {
            attributes.addValue("class", "md-table");
            attributes.replaceValue("cellpadding", "6");
            attributes.replaceValue("cellspacing", "0");
            attributes.replaceValue("border", "0");
        } else if (node instanceof TableCell cell) {
            attributes.addValue("class", cell.isHeader() ? "md-th" : "md-td");
        }
    }

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
        String html = MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown));
        return JexlToHtmlConverter.highlightCodeBlocks(html);
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

        Color bg = getUiColor(isDark ? new Color(30, 30, 30) : Color.WHITE, "TextPane.background");
        Color fg = getUiColor(isDark ? Color.LIGHT_GRAY : Color.BLACK, "TextPane.foreground");

        textPane.setBackground(bg);
        textPane.setForeground(fg);
        textPane.setOpaque(true);

        String bodyRule =
                String.format(
                        "body { background-color: %s; color: %s; margin: %dpx; }",
                        toHex(bg), toHex(fg), marginPx);
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
            if (view.getClass() == InlineView.class) {
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
     * Installs a standard context menu (Cut/Paste when editable, Copy, Select All) on a text
     * component.
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
                        if (component.isEditable()) {
                            menu.add(new JMenuItem(new DefaultEditorKit.CutAction()))
                                    .setText("Cut");
                        }
                        menu.add(new JMenuItem(new DefaultEditorKit.CopyAction())).setText("Copy");
                        if (component.isEditable()) {
                            menu.add(new JMenuItem(new DefaultEditorKit.PasteAction()))
                                    .setText("Paste");
                        }
                        menu.addSeparator();
                        JMenuItem selectAll = new JMenuItem("Select All");
                        selectAll.addActionListener(ae -> component.selectAll());
                        menu.add(selectAll);
                        menu.show(e.getComponent(), e.getX(), e.getY());
                    }
                });
    }

    /**
     * Copies the given text to the system clipboard, ignoring errors in headless or restricted
     * environments.
     *
     * @param text the text to copy
     */
    public static void copyToClipboard(String text) {
        if (text == null) {
            return;
        }
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        } catch (Exception ignored) {
            // Ignore clipboard access errors in headless or restricted environments
        }
    }

    /**
     * Escapes text for safe inclusion in HTML bodies.
     *
     * @param text the raw text
     * @return HTML entity escaped text
     */
    /**
     * Resolves the first non-null {@link Color} from {@link UIManager} for the given keys, or
     * returns the fallback color.
     *
     * @param fallback the fallback color if none of the keys resolve
     * @param uiKeys the UIManager color keys to check in order
     * @return the resolved color
     */
    public static Color getUiColor(Color fallback, String... uiKeys) {
        if (uiKeys != null) {
            for (String key : uiKeys) {
                Color color = UIManager.getColor(key);
                if (color != null) {
                    return color;
                }
            }
        }
        return fallback;
    }

    /**
     * Formats a {@link Color} as a 6-digit hex string (e.g. {@code #1a2b3c}).
     *
     * @param color the color to format
     * @return the hex color string
     */
    public static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    public static String escapeHtml(String text) {
        return text == null ? "" : StringEscapeUtils.escapeHtml4(text);
    }
}
