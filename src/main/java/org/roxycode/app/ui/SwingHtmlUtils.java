package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Node;
import java.util.Set;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.html.MutableAttributes;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultCaret;
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
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwingHtmlUtils {

    private static final Logger log = LoggerFactory.getLogger(SwingHtmlUtils.class);

    private static final DataHolder FLEXMARK_OPTIONS = new MutableDataSet().set(Parser.EXTENSIONS,
            Arrays.asList(TablesExtension.create(), StrikethroughExtension.create(), YamlFrontMatterExtension.create(),
                    new MarkdownTableAttributeExtension(), new SyntaxHighlightingExtension()))
            .toImmutable();
    private static final Parser MARKDOWN_PARSER = Parser.builder(FLEXMARK_OPTIONS).build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder(FLEXMARK_OPTIONS).build();
    private static final Color TRANSPARENT_CARET_COLOR = new Color(0, 0, 0, 0);
    private static volatile boolean testMode = Boolean.getBoolean("roxycode.testMode");

    private static final Cache<Boolean, Theme> RSYNTAX_THEME_CACHE = Caffeine.newBuilder().maximumSize(2).build();

    private SwingHtmlUtils() {
    }

    static final class NonPaintingCaret extends DefaultCaret {
        private static final long serialVersionUID = 1L;

        NonPaintingCaret() {
            setBlinkRate(0);
        }

        @Override
        public void paint(Graphics g) {
            // Do not paint a visible text cursor in read-only HTML panes
        }
    }

    public static void configureReadOnlyCaret(JTextComponent component) {
        if (!(component.getCaret() instanceof NonPaintingCaret)) {
            component.setCaret(new NonPaintingCaret());
        }
        component.getCaret().setBlinkRate(0);
        component.setCaretColor(TRANSPARENT_CARET_COLOR);
    }

    private static final class MarkdownTableAttributeExtension implements HtmlRenderer.HtmlRendererExtension {
        @Override
        public void rendererOptions(MutableDataHolder options) {
        }

        @Override
        public void extend(HtmlRenderer.Builder htmlRendererBuilder, String rendererType) {
            htmlRendererBuilder.attributeProviderFactory(new IndependentAttributeProviderFactory() {
                @Override
                public AttributeProvider apply(LinkResolverContext context) {
                    return (node, part, attributes) -> setTableNodeAttributes(node, attributes);
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
            String cellClass;
            if (cell.isHeader()) {
                cellClass = "md-th";
            } else {
                cellClass = "md-td";
            }
            attributes.addValue("class", cellClass);
        }
    }

    public static String renderMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown));
    }

    public static Parser getMarkdownParser() {
        return MARKDOWN_PARSER;
    }

    public static JEditorPane createHtmlEditorPane() {
        JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        configureReadOnlyCaret(pane);
        installTextContextMenu(pane);
        return pane;
    }

    public static void addChangeListener(JTextComponent component, Runnable onChange) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onChange.run();
            }
        });
    }

    public static void applyTintedBannerStyle(JComponent banner, Color accent, int topBottomPad, int leftPad,
            int rightPad) {
        Color tintedBg = getTintedBackground(accent, 0.12);
        banner.setOpaque(true);
        banner.setBackground(tintedBg);
        banner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                BorderFactory.createEmptyBorder(topBottomPad, leftPad, topBottomPad, rightPad)));
        banner.putClientProperty(FlatClientProperties.STYLE, "background: " + toHex(tintedBg));
    }

    public static void applyTheme(JEditorPane textPane, int marginPx) {
        HTMLEditorKit kit = new HTMLEditorKit() {
            private static final long serialVersionUID = 1L;
            private final transient ViewFactory factory = new WrappingHtmlFactory();

            @Override
            public ViewFactory getViewFactory() {
                return factory;
            }
        };
        StyleSheet sheet = new StyleSheet();
        boolean isDark = FlatLaf.isLafDark();
        String cssPath;
        if (isDark) {
            cssPath = "/css/chat-styles-dark.css";
        } else {
            cssPath = "/css/chat-styles-light.css";
        }
        URL cssUrl = SwingHtmlUtils.class.getResource(cssPath);
        if (cssUrl != null) {
            sheet.importStyleSheet(cssUrl);
        }

        Color bgFallback;
        if (isDark) {
            bgFallback = new Color(30, 30, 30);
        } else {
            bgFallback = Color.WHITE;
        }
        Color bg = getUiColor(bgFallback, "TextPane.background");

        Color fgFallback;
        if (isDark) {
            fgFallback = Color.LIGHT_GRAY;
        } else {
            fgFallback = Color.BLACK;
        }
        Color fg = getUiColor(fgFallback, "TextPane.foreground");
        textPane.setBackground(bg);
        textPane.setForeground(fg);
        textPane.setOpaque(true);

        sheet.addRule(String.format("body { background-color: %s; color: %s; margin: %dpx; }", toHex(bg), toHex(fg),
                marginPx));
        kit.setStyleSheet(sheet);
        kit.setDefaultCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        textPane.setEditorKit(kit);
        configureReadOnlyCaret(textPane);
    }

    private static boolean isNoWrap(Element elem) {
        for (Element curr = elem; curr != null; curr = curr.getParentElement()) {
            AttributeSet attrs = curr.getAttributes();
            if (attrs != null) {
                if (attrs.isDefined(HTML.Attribute.NOWRAP))
                    return true;
                Object ws = attrs.getAttribute(CSS.Attribute.WHITE_SPACE);
                if (ws != null && "nowrap".equalsIgnoreCase(ws.toString()))
                    return true;
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
                        if (axis == View.X_AXIS) {
                            return 0;
                        }
                        return super.getMinimumSpan(axis);
                    }
                };
            }
            if (view instanceof ParagraphView) {
                return new ParagraphView(elem) {
                    @Override
                    public float getMinimumSpan(int axis) {
                        if (axis == View.X_AXIS) {
                            return 0;
                        }
                        return super.getMinimumSpan(axis);
                    }
                };
            }
            if (view instanceof BlockView blockView && !"body".equalsIgnoreCase(elem.getName())) {
                return new BlockView(elem, blockView.getAxis()) {
                    @Override
                    public float getMinimumSpan(int axis) {
                        if (axis == View.X_AXIS) {
                            return 0;
                        }
                        return super.getMinimumSpan(axis);
                    }
                };
            }
            return view;
        }
    }

    public static void installTextContextMenu(JTextComponent component) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger())
                    showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger())
                    showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                component.requestFocusInWindow();
                JPopupMenu menu = new JPopupMenu();
                if (component.isEditable()) {
                    menu.add(new JMenuItem(new DefaultEditorKit.CutAction())).setText("Cut");
                }
                menu.add(new JMenuItem(new DefaultEditorKit.CopyAction())).setText("Copy");
                if (component.isEditable()) {
                    menu.add(new JMenuItem(new DefaultEditorKit.PasteAction())).setText("Paste");
                }
                menu.addSeparator();
                JMenuItem selectAll = new JMenuItem("Select All");
                selectAll.addActionListener(ae -> component.selectAll());
                menu.add(selectAll);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    public static void setTestMode(boolean enabled) {
        testMode = enabled;
    }

    public static boolean isTestMode() {
        return testMode;
    }

    public static void copyToClipboard(String text) {
        if (text == null || testMode) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception ignored) {
        }
    }

    public static Timer showCopyButtonFeedback(JButton button, Timer existingTimer, String copiedText, Icon copiedIcon,
            String originalText, Icon originalIcon, int delayMs) {
        if (existingTimer != null && existingTimer.isRunning()) {
            existingTimer.stop();
        }
        button.setText(copiedText);
        button.setIcon(copiedIcon);
        Timer timer = new Timer(delayMs, e -> {
            button.setText(originalText);
            button.setIcon(originalIcon);
        });
        timer.setRepeats(false);
        timer.start();
        return timer;
    }

    public static javax.swing.border.Border createThemeMatteBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createMatteBorder(top, left, bottom, right,
                getUiColor(Color.GRAY, "Component.borderColor"));
    }

    public static Color getUiColor(Color fallback, String... uiKeys) {
        if (uiKeys != null) {
            for (String key : uiKeys) {
                Color color = UIManager.getColor(key);
                if (color != null)
                    return color;
            }
        }
        return fallback;
    }

    public static Color getAccentColor() {
        return getUiColor(new Color(0x00, 0x7a, 0xcc), "Component.accentColor", "Actions.Blue");
    }

    public static Color getMutedForegroundColor() {
        return getUiColor(Color.GRAY, "Label.disabledForeground", "Component.infoForeground");
    }

    public static Color blendColor(Color foreground, Color background, double foregroundRatio) {
        double clamped = Math.clamp(foregroundRatio, 0.0, 1.0);
        double bgRatio = 1.0 - clamped;
        int r = (int) Math.round(foreground.getRed() * clamped + background.getRed() * bgRatio);
        int g = (int) Math.round(foreground.getGreen() * clamped + background.getGreen() * bgRatio);
        int b = (int) Math.round(foreground.getBlue() * clamped + background.getBlue() * bgRatio);
        return new Color(Math.clamp(r, 0, 255), Math.clamp(g, 0, 255), Math.clamp(b, 0, 255));
    }

    public static Color getTintedBackground(Color tint, double tintRatio) {
        return blendColor(tint, getUiColor(new Color(0xf5, 0xf5, 0xf5), "Panel.background"), tintRatio);
    }

    public static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return StringEscapeUtils.escapeHtml4(text);
    }

    public static boolean isDarkTheme(String themeName) {
        if (themeName != null && !themeName.isBlank()) {
            String lower = themeName.toLowerCase(Locale.ROOT);
            if (lower.contains("dark") || "darcula".equals(lower)) {
                return true;
            }
            if (lower.contains("light") || "intellij".equals(lower)) {
                return false;
            }
        }
        return FlatLaf.isLafDark();
    }

    public static void applyRSyntaxTheme(RSyntaxTextArea textArea, SettingsService settingsService) {
        String theme = null;
        if (settingsService != null && settingsService.getSettings() != null) {
            theme = settingsService.getSettings().getTheme();
        }
        applyRSyntaxTheme(textArea, theme);
    }

    public static void applyRSyntaxTheme(RSyntaxTextArea textArea, String themeName) {
        if (textArea == null) {
            return;
        }
        try {
            getRSyntaxTheme(isDarkTheme(themeName)).apply(textArea);
        } catch (IOException ioe) {
            log.error("Failed to load RSyntaxTextArea theme: {}", ioe.getMessage());
        }
    }

    private static Theme getRSyntaxTheme(boolean dark) throws IOException {
        try {
            return RSYNTAX_THEME_CACHE.get(dark, isDark -> {
                String themePath;
                if (isDark) {
                    themePath = "/org/fife/ui/rsyntaxtextarea/themes/dark.xml";
                } else {
                    themePath = "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
                }
                try (InputStream in = SwingHtmlUtils.class.getResourceAsStream(themePath)) {
                    return Theme.load(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static final class SyntaxHighlightingExtension implements HtmlRenderer.HtmlRendererExtension {
        @Override
        public void rendererOptions(MutableDataHolder options) {
        }

        @Override
        public void extend(HtmlRenderer.Builder htmlRendererBuilder, String rendererType) {
            htmlRendererBuilder.nodeRendererFactory(new CodeBlockNodeRenderer.Factory());
        }
    }

    private static final class CodeBlockNodeRenderer implements NodeRenderer {
        @Override
        public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
            return Set.of(new NodeRenderingHandler<>(FencedCodeBlock.class, this::render),
                    new NodeRenderingHandler<>(IndentedCodeBlock.class, this::render));
        }

        private void render(Node node, NodeRendererContext context, HtmlWriter html) {
            String code = ((Block) node).getContentChars().toString();
            String language = "";
            if (node instanceof FencedCodeBlock fenced) {
                language = fenced.getInfo().toString();
            }
            html.raw(JexlToHtmlConverter.highlight(code, language));
        }

        static class Factory implements NodeRendererFactory {
            @Override
            public NodeRenderer apply(DataHolder options) {
                return new CodeBlockNodeRenderer();
            }
        }
    }
}