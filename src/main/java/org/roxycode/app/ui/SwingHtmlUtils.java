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
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
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
import javax.swing.text.html.ImageView;
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

    private static final DataHolder FLEXMARK_OPTIONS = new MutableDataSet().set(HtmlRenderer.ESCAPE_HTML, true)
            .set(Parser.EXTENSIONS,
                    Arrays.asList(TablesExtension.create(), StrikethroughExtension.create(),
                            YamlFrontMatterExtension.create(), new MarkdownTableAttributeExtension(),
                            new SyntaxHighlightingExtension()))
            .toImmutable();
    private static final Parser MARKDOWN_PARSER = Parser.builder(FLEXMARK_OPTIONS).build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder(FLEXMARK_OPTIONS).build();
    private static final Color TRANSPARENT_CARET_COLOR = new Color(0, 0, 0, 0);
    static final int IMAGE_HORIZONTAL_PADDING = 48;
    private static final String RESPONSIVE_RESIZE_LISTENER_KEY = "roxycode.responsiveResizeListener";
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

    static void configureReadOnlyCaret(JTextComponent component) {
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

    static String renderMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown));
    }

    public static Parser getMarkdownParser() {
        return MARKDOWN_PARSER;
    }

    static JEditorPane createHtmlEditorPane() {
        JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        configureReadOnlyCaret(pane);
        installTextContextMenu(pane);
        return pane;
    }

    static void addChangeListener(JTextComponent component, Runnable onChange) {
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

    static void applyTintedBannerStyle(JComponent banner, Color accent, int topBottomPad, int leftPad, int rightPad) {
        Color tintedBg = getTintedBackground(accent, 0.12);
        banner.setOpaque(true);
        banner.setBackground(tintedBg);
        banner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                BorderFactory.createEmptyBorder(topBottomPad, leftPad, topBottomPad, rightPad)));
        banner.putClientProperty(FlatClientProperties.STYLE, "background: " + toHex(tintedBg));
    }

    static void applyTheme(JEditorPane textPane, int marginPx) {
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

        Color bg = getUiColor(Color.WHITE, "TextPane.background");
        Color fg = getUiColor(Color.BLACK, "TextPane.foreground");
        textPane.setBackground(bg);
        textPane.setForeground(fg);
        textPane.setOpaque(true);

        sheet.addRule(String.format("body { background-color: %s; color: %s; margin: %dpx; }", toHex(bg), toHex(fg),
                marginPx));
        kit.setStyleSheet(sheet);
        kit.setDefaultCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        textPane.setEditorKit(kit);
        configureReadOnlyCaret(textPane);
        installResponsiveResizeListener(textPane);
    }

    private static void installResponsiveResizeListener(JEditorPane textPane) {
        if (Boolean.TRUE.equals(textPane.getClientProperty(RESPONSIVE_RESIZE_LISTENER_KEY))) {
            return;
        }
        textPane.putClientProperty(RESPONSIVE_RESIZE_LISTENER_KEY, Boolean.TRUE);
        textPane.addComponentListener(new ComponentAdapter() {
            private int lastWidth = -1;

            @Override
            public void componentResized(ComponentEvent e) {
                int currentWidth = textPane.getWidth();
                if (currentWidth > 0 && currentWidth != lastWidth) {
                    lastWidth = currentWidth;
                    invalidateResponsiveImages(textPane);
                }
            }
        });
    }

    static boolean invalidateResponsiveImages(JEditorPane textPane) {
        if (textPane == null || textPane.getUI() == null) {
            return false;
        }
        View rootView = textPane.getUI().getRootView(textPane);
        if (rootView == null) {
            return false;
        }
        Element rootElement = null;
        if (textPane.getDocument() != null) {
            rootElement = textPane.getDocument().getDefaultRootElement();
        }
        boolean hasResponsiveView = invalidateResponsiveImageViews(rootView);
        if (!hasResponsiveView && !hasImageElement(rootElement)) {
            return false;
        }
        if (textPane.getWidth() > 0 && textPane.getHeight() > 0) {
            rootView.setSize(textPane.getWidth(), textPane.getHeight());
        }
        textPane.revalidate();
        textPane.repaint();
        return true;
    }

    private static boolean hasImageElement(Element elem) {
        if (elem == null) {
            return false;
        }
        if (HTML.Tag.IMG.toString().equalsIgnoreCase(elem.getName())) {
            return true;
        }
        int count = elem.getElementCount();
        for (int i = 0; i < count; i++) {
            if (hasImageElement(elem.getElement(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean invalidateResponsiveImageViews(View view) {
        if (view instanceof ResponsiveImageView) {
            view.preferenceChanged(null, true, true);
            return true;
        }
        boolean found = false;
        int count = view.getViewCount();
        for (int i = 0; i < count; i++) {
            View child = view.getView(i);
            if (child != null && invalidateResponsiveImageViews(child)) {
                found = true;
            }
        }
        return found;
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

    private static float zeroHorizontalMinimumSpan(int axis, float defaultSpan) {
        if (axis == View.X_AXIS) {
            return 0;
        }
        return defaultSpan;
    }

    static View wrapWithZeroMinSpan(View view, Element elem) {
        if (view == null) {
            return null;
        }
        if (view.getClass() == InlineView.class) {
            return new InlineView(elem) {
                @Override
                public float getMinimumSpan(int axis) {
                    return zeroHorizontalMinimumSpan(axis, super.getMinimumSpan(axis));
                }
            };
        }
        if (view.getClass() == ParagraphView.class) {
            return new ParagraphView(elem) {
                @Override
                public float getMinimumSpan(int axis) {
                    return zeroHorizontalMinimumSpan(axis, super.getMinimumSpan(axis));
                }
            };
        }
        if (view.getClass() == BlockView.class && (elem == null || !"body".equalsIgnoreCase(elem.getName()))) {
            BlockView bv = (BlockView) view;
            return new BlockView(elem, bv.getAxis()) {
                @Override
                public float getMinimumSpan(int axis) {
                    return zeroHorizontalMinimumSpan(axis, super.getMinimumSpan(axis));
                }
            };
        }
        return view;
    }

    static final class ResponsiveImageView extends ImageView {

        ResponsiveImageView(Element elem) {
            super(elem);
            setLoadsSynchronously(true);
        }

        @Override
        public void setParent(View parent) {
            super.setParent(parent);
            if (parent != null) {
                super.getPreferredSpan(View.X_AXIS);
            }
        }

        @Override
        public float getMinimumSpan(int axis) {
            if (axis == View.X_AXIS) {
                return 0f;
            }
            return getPreferredSpan(axis);
        }

        @Override
        public float getPreferredSpan(int axis) {
            int intrinsicWidth = resolveIntrinsicDimension(View.X_AXIS);
            int intrinsicHeight = resolveIntrinsicDimension(View.Y_AXIS);
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                return super.getPreferredSpan(axis);
            }
            int availableWidth = resolveAvailableWidth();
            boolean scaledDown = availableWidth > 0 && intrinsicWidth > availableWidth;
            if (axis == View.X_AXIS) {
                if (scaledDown) {
                    return availableWidth;
                }
                return intrinsicWidth;
            }
            if (axis == View.Y_AXIS) {
                if (scaledDown) {
                    double scale = (double) availableWidth / intrinsicWidth;
                    return Math.max(1f, Math.round(intrinsicHeight * scale));
                }
                return intrinsicHeight;
            }
            return super.getPreferredSpan(axis);
        }

        @Override
        public float getMaximumSpan(int axis) {
            return getPreferredSpan(axis);
        }

        @Override
        public void paint(Graphics g, Shape a) {
            Image image = getImage();
            if (image == null || a == null) {
                super.paint(g, a);
                return;
            }
            Rectangle bounds;
            if (a instanceof Rectangle rect) {
                bounds = rect;
            } else {
                bounds = a.getBounds();
            }
            if (bounds.width <= 0 || bounds.height <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
            } finally {
                g2.dispose();
            }
        }

        int resolveIntrinsicDimension(int axis) {
            HTML.Attribute attr;
            if (axis == View.X_AXIS) {
                attr = HTML.Attribute.WIDTH;
            } else {
                attr = HTML.Attribute.HEIGHT;
            }
            AttributeSet attrs = getElement().getAttributes();
            int fromAttr = parsePositiveIntAttribute(attrs, attr);
            if (fromAttr > 0) {
                return fromAttr;
            }
            Image image = getImage();
            if (image != null) {
                int fromImage;
                if (axis == View.X_AXIS) {
                    fromImage = image.getWidth(null);
                } else {
                    fromImage = image.getHeight(null);
                }
                if (fromImage > 0) {
                    return fromImage;
                }
            }
            return Math.round(super.getPreferredSpan(axis));
        }

        int resolveAvailableWidth() {
            Container container = getContainer();
            if (container == null) {
                return 0;
            }
            int width = container.getWidth();
            if (width <= 0 && container.getParent() != null) {
                width = container.getParent().getWidth();
            }
            if (width <= 0) {
                return 0;
            }
            Insets insets = container.getInsets();
            int horizontalInsets = 0;
            if (insets != null) {
                horizontalInsets = insets.left + insets.right;
            }
            int available = width - horizontalInsets - IMAGE_HORIZONTAL_PADDING;
            return Math.max(0, available);
        }

        private static int parsePositiveIntAttribute(AttributeSet attrs, HTML.Attribute attr) {
            if (attrs == null) {
                return -1;
            }
            Object raw = attrs.getAttribute(attr);
            if (raw == null) {
                return -1;
            }
            String text = raw.toString().trim();
            if (text.endsWith("px") || text.endsWith("PX")) {
                text = text.substring(0, text.length() - 2).trim();
            }
            try {
                int value = Integer.parseInt(text);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Ignore non-integer width/height attribute
            }
            return -1;
        }
    }

    private static final class WrappingHtmlFactory extends HTMLEditorKit.HTMLFactory {
        @Override
        public View create(Element elem) {
            View view = super.create(elem);
            if (view instanceof ImageView) {
                return new ResponsiveImageView(elem);
            }
            if (isNoWrap(elem)) {
                return view;
            }
            return wrapWithZeroMinSpan(view, elem);
        }
    }

    static void installTextContextMenu(JTextComponent component) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
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

    static void setTestMode(boolean enabled) {
        testMode = enabled;
    }

    static void copyToClipboard(String text) {
        if (text == null || testMode) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception ignored) {
        }
    }

    static Timer showCopyButtonFeedback(JButton button, Timer existingTimer, String copiedText, Icon copiedIcon,
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

    static javax.swing.border.Border createThemeMatteBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createMatteBorder(top, left, bottom, right,
                getUiColor(Color.GRAY, "Component.borderColor"));
    }

    static Color getUiColor(Color fallback, String... uiKeys) {
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

    static Color getAccentColor() {
        return getUiColor(new Color(0x00, 0x7a, 0xcc), "Component.accentColor", "Actions.Blue");
    }

    static Color getWarningColor() {
        return getUiColor(new Color(0xd9, 0x77, 0x06), "Component.warning.borderColor", "Actions.Yellow");
    }

    static Color getMutedForegroundColor() {
        return getUiColor(Color.GRAY, "Label.disabledForeground", "Component.infoForeground");
    }

    static Color blendColor(Color foreground, Color background, double foregroundRatio) {
        double clamped = Math.clamp(foregroundRatio, 0.0, 1.0);
        double bgRatio = 1.0 - clamped;
        int r = (int) Math.round(foreground.getRed() * clamped + background.getRed() * bgRatio);
        int g = (int) Math.round(foreground.getGreen() * clamped + background.getGreen() * bgRatio);
        int b = (int) Math.round(foreground.getBlue() * clamped + background.getBlue() * bgRatio);
        return new Color(Math.clamp(r, 0, 255), Math.clamp(g, 0, 255), Math.clamp(b, 0, 255));
    }

    static Color getTintedBackground(Color tint, double tintRatio) {
        return blendColor(tint, getUiColor(new Color(0xf5, 0xf5, 0xf5), "Panel.background"), tintRatio);
    }

    static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return StringEscapeUtils.escapeHtml4(text);
    }

    static boolean isDarkTheme(String themeName) {
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

    static void applyRSyntaxTheme(RSyntaxTextArea textArea, SettingsService settingsService) {
        String theme = null;
        if (settingsService != null && settingsService.getSettings() != null) {
            theme = settingsService.getSettings().getTheme();
        }
        applyRSyntaxTheme(textArea, theme);
    }

    static void applyRSyntaxTheme(RSyntaxTextArea textArea, String themeName) {
        if (textArea == null) {
            return;
        }
        try {
            getRSyntaxTheme(isDarkTheme(themeName)).apply(textArea);
        } catch (IOException ioe) {
            log.error("Failed to load RSyntaxTextArea theme: {}", ioe.getMessage());
        }
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
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
                    new NodeRenderingHandler<>(IndentedCodeBlock.class, this::render),
                    new NodeRenderingHandler<>(Code.class, this::renderInlineCode));
        }

        @SuppressWarnings("PMD.UnusedFormalParameter")
        private void renderInlineCode(Code node, NodeRendererContext context, HtmlWriter html) {
            html.raw("<code>&nbsp;");
            html.text(node.getText().normalizeEOL());
            html.raw("&nbsp;</code>");
        }

        @SuppressWarnings("PMD.UnusedFormalParameter")
        private void render(Node node, NodeRendererContext context, HtmlWriter html) {
            String code = ((Block) node).getContentChars().toString();
            String language = "";
            if (node instanceof FencedCodeBlock fenced) {
                language = fenced.getInfo().toString();
            }
            if (PlantUmlRenderer.isDiagramLanguage(language)) {
                Optional<String> diagramHtml = PlantUmlRenderer.renderDiagramHtml(code, language, FlatLaf.isLafDark());
                if (diagramHtml.isPresent()) {
                    html.raw(diagramHtml.get());
                    return;
                }
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