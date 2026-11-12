package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.SystemFileChooser;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Desktop;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevelOption;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Shared Swing UI component builders and FlatLaf style helpers to reduce boilerplate.
 */
final class UiComponents {
    private static final Logger log = LoggerFactory.getLogger(UiComponents.class);
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String ROW_CENTER = "[center]";
    static final String ROXY_LOGO_RESOURCE = "/static/images/roxy.png";
    static final int INFO_TOOLTIP_DISMISS_DELAY_MS = 20_000;
    static final String ACCENT_BUTTON_STYLE = "background: $Component.accentColor; focusedBackground: $Component.accentColor; hoverBackground: darken($Component.accentColor, 8%); pressedBackground: darken($Component.accentColor, 14%); foreground: #ffffff";
    static final String GREEN_BUTTON_STYLE = "background: $Actions.Green; focusedBackground: $Actions.Green; hoverBackground: darken($Actions.Green, 8%); pressedBackground: darken($Actions.Green, 14%); foreground: #ffffff";
    static final String SUBTLE_GREEN_BUTTON_STYLE = "foreground: $Actions.Green; hoverBackground: fade($Actions.Green, 12%); pressedBackground: fade($Actions.Green, 20%); borderColor: fade($Actions.Green, 45%); focusedBorderColor: $Actions.Green; hoverBorderColor: $Actions.Green";
    static final String RED_BUTTON_STYLE = "background: $Actions.Red; focusedBackground: $Actions.Red; hoverBackground: darken($Actions.Red, 8%); pressedBackground: darken($Actions.Red, 14%); foreground: #ffffff";
    static final String SUBTLE_RED_BUTTON_STYLE = "foreground: $Actions.Red; hoverBackground: fade($Actions.Red, 12%); pressedBackground: fade($Actions.Red, 20%); borderColor: fade($Actions.Red, 45%); focusedBorderColor: $Actions.Red; hoverBorderColor: $Actions.Red";
    static final Color AMBER_LIGHT = new Color(0xd9, 0x77, 0x06);
    static final Color AMBER_DARK = new Color(0xf5, 0x9e, 0x0b);
    static final Color INFO_BLUE_LIGHT = new Color(0x25, 0x63, 0xeb);
    static final Color INFO_BLUE_DARK = new Color(0x3b, 0x82, 0xf6);
    static final String THINKING_LEVEL_TOOLTIP_HTML = "<html><body style='width: 310px; padding: 4px;'>"
            + "<b>Thinking Level</b><br>" + "Controls the reasoning depth used by Gemini models:<br><br>"
            + "• <b>Adaptive (Phase-Aware)</b>: Dynamically adjusts thinking per sub-turn:<br>"
            + "&nbsp;&nbsp;– <b>PLAN</b>: HIGH while designing blueprint → LOW after submitting plan<br>"
            + "&nbsp;&nbsp;– <b>CODE</b>: LOW for fast execution → HIGH after any tool/build/test failure<br>"
            + "&nbsp;&nbsp;– <b>REVIEW</b>: MEDIUM for balanced diff and test auditing<br>"
            + "&nbsp;&nbsp;– <b>EXPLORE</b>: LOW on initial discovery → MEDIUM on synthesis<br>"
            + "• <b>Fixed Levels (Minimal/None, Low, Medium, High)</b>: Uses a constant thinking budget on every turn."
            + "</body></html>";
    static final int CHAT_THUMBNAIL_MAX_DIM = 180;
    private static final String TEMP_THUMB_DIR_PREFIX = "roxycode-thumbs-";
    private static Path tempThumbnailDir;

    record HtmlImagePreview(String url, int width, int height) {
    }

    private UiComponents() {
    }

    /**
     * Loads an image from a classpath resource, returning null if unavailable.
     */
    @Nullable
    static BufferedImage loadClasspathImage(String resourcePath) {
        try {
            URL url = UiComponents.class.getResource(resourcePath);
            if (url != null) {
                return ImageIO.read(url);
            }
        } catch (IOException e) {
            log.warn("Failed to load image resource {}: {}", resourcePath, e.getMessage());
        }
        return null;
    }

    /**
     * Scales an image proportionally into an ARGB thumbnail fitting within {@code maxDimension} while preserving aspect
     * ratio.
     */
    static BufferedImage createThumbnail(BufferedImage source, int maxDimension) {
        int safeMax = Math.max(1, maxDimension);
        if (source == null) {
            return new BufferedImage(safeMax, safeMax, BufferedImage.TYPE_INT_ARGB);
        }
        if (source.getWidth() <= safeMax && source.getHeight() <= safeMax) {
            return source;
        }
        try {
            return Thumbnails.of(source).size(safeMax, safeMax).asBufferedImage();
        } catch (IOException e) {
            log.warn("Failed to create thumbnail: {}", e.getMessage());
            return source;
        }
    }

    /**
     * Encodes scaled aspect-ratio-preserving PNG thumbnails as base64 strings for persistence and HTML rendering.
     */
    static List<String> encodeThumbnailsToBase64Png(List<BufferedImage> images, int maxDimension) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<String> encoded = new ArrayList<>(images.size());
        for (BufferedImage image : images) {
            if (image == null) {
                continue;
            }
            try {
                BufferedImage thumb = createThumbnail(image, maxDimension);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(thumb, "png", baos);
                encoded.add(Base64.getEncoder().encodeToString(baos.toByteArray()));
            } catch (Exception e) {
                log.warn("Failed to encode chat thumbnail: {}", e.getMessage());
            }
        }
        return List.copyOf(encoded);
    }

    @Nullable
    static HtmlImagePreview writeBase64ThumbnailToTempUrl(String base64Png) {
        if (StringUtils.isBlank(base64Png)) {
            return null;
        }
        try {
            byte[] pngBytes = Base64.getDecoder().decode(base64Png.trim());
            Path outputDir = getOrCreateTempThumbnailDir();
            String fileName = "thumb-" + PathUtils.computeSha256Hex(base64Png, 16) + ".png";
            return writePngBytesToTempPreview(pngBytes, outputDir, fileName);
        } catch (Exception e) {
            log.debug("Failed to decode thumbnail image for HTML preview: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    static HtmlImagePreview writePngBytesToTempPreview(byte[] pngBytes, Path outputDir, String fileName)
            throws IOException {
        if (pngBytes == null || pngBytes.length == 0) {
            return null;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }
        Path pngFile = outputDir.resolve(fileName);
        if (!Files.exists(pngFile)) {
            Files.write(pngFile, pngBytes);
            pngFile.toFile().deleteOnExit();
        }
        return new HtmlImagePreview(pngFile.toUri().toURL().toExternalForm(), image.getWidth(), image.getHeight());
    }

    private static synchronized Path getOrCreateTempThumbnailDir() throws IOException {
        if (tempThumbnailDir != null && Files.isDirectory(tempThumbnailDir)) {
            return tempThumbnailDir;
        }
        Path created = Files.createTempDirectory(TEMP_THUMB_DIR_PREFIX);
        created.toFile().deleteOnExit();
        tempThumbnailDir = created;
        return created;
    }

    /**
     * Creates a rounded image panel from a classpath resource path.
     */
    static JPanel createRoundedImagePanel(String resourcePath, int size, int arc) {
        return createRoundedImagePanel(loadClasspathImage(resourcePath), size, arc);
    }

    /**
     * Creates a rounded image panel that clips and renders the image directly.
     */
    static JPanel createRoundedImagePanel(@Nullable Image image, int size, int arc) {
        int innerSize = Math.max(1, size - 4);
        Image scaledImage = image;
        if (image instanceof BufferedImage bufferedImage) {
            try {
                scaledImage = Thumbnails.of(bufferedImage).size(innerSize, innerSize).asBufferedImage();
            } catch (IOException e) {
                log.warn("Failed to pre-scale rounded panel image: {}", e.getMessage());
            }
        }
        Image finalImage = scaledImage;
        JPanel panel = new JPanel() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int w = getWidth();
                int h = getHeight();
                if (w <= 4 || h <= 4) {
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    RoundRectangle2D.Float clipShape = new RoundRectangle2D.Float(2, 2, w - 4, h - 4, arc, arc);
                    if (finalImage != null) {
                        Shape oldClip = g2.getClip();
                        g2.clip(clipShape);
                        g2.drawImage(finalImage, 2, 2, w - 4, h - 4, null);
                        g2.setClip(oldClip);
                    } else {
                        g2.setColor(SwingHtmlUtils.getUiColor(new Color(100, 100, 255), "Component.accentColor"));
                        g2.fill(clipShape);
                    }
                    g2.setColor(SwingHtmlUtils.getUiColor(new Color(80, 80, 80), "Component.borderColor"));
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(2, 2, w - 5, h - 5, arc, arc);
                } finally {
                    g2.dispose();
                }
            }
        };
        panel.setPreferredSize(new Dimension(size, size));
        panel.setOpaque(false);
        return panel;
    }

    /**
     * Wraps a JEditorPane in a borderless JScrollPane.
     */
    static JScrollPane createHtmlScrollPane(JEditorPane editorPane) {
        JScrollPane scrollPane = new JScrollPane(editorPane);
        scrollPane.setBorder(null);
        return scrollPane;
    }

    /**
     * Creates a copy button with the standard clippy icon.
     */
    static JButton createCopyButton(String text, boolean enabled, Runnable onCopy) {
        JButton button = new JButton(text, FontIcon.of(Codicons.CLIPPY, 14, resolveActionButtonIconColor(null)));
        button.setEnabled(enabled);
        button.addActionListener(e -> onCopy.run());
        return button;
    }

    static JButton createProjectSwitcherButton(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setIconTextGap(6);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        return button;
    }

    static Color resolveForegroundColor() {
        return SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Label.foreground");
    }

    static JButton createIconButton(Codicons icon, String tooltip, java.awt.event.ActionListener listener) {
        JButton button = new JButton(FontIcon.of(icon, 16, resolveForegroundColor()));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        return button;
    }

    /**
     * Opens a URL in the system default browser, falling back to copying the URL to the clipboard.
     */
    static void openUrlOrCopyToClipboard(String url) {
        if (StringUtils.isBlank(url)) {
            return;
        }
        String trimmed = url.trim();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(trimmed));
                return;
            }
        } catch (Exception e) {
            log.debug("Browser launch failed for {}, falling back to clipboard: {}", trimmed, e.getMessage());
        }
        SwingHtmlUtils.copyToClipboard(trimmed);
    }

    /**
     * Copies text to the clipboard and triggers temporary "Copied!" button feedback.
     */
    static Timer copyWithFeedback(JButton button, @Nullable Timer existingTimer, String content, String originalText,
            int delayMs) {
        SwingHtmlUtils.copyToClipboard(content);
        Color iconColor = resolveActionButtonIconColor(null);
        return SwingHtmlUtils.showCopyButtonFeedback(button, existingTimer, "Copied!",
                FontIcon.of(Codicons.CHECK, 14, iconColor), originalText, FontIcon.of(Codicons.CLIPPY, 14, iconColor),
                delayMs);
    }

    /**
     * Creates a JPanel that implements Scrollable and tracks viewport width.
     */
    static JPanel createScrollableCardsPanel(LayoutManager layout) {
        return new ScrollableCardsPanel(layout);
    }

    /**
     * Wraps a component in a borderless, vertical-only JScrollPane with standard unit increment.
     */
    static JScrollPane createBorderlessScrollPane(JPanel view) {
        JScrollPane scrollPane = new JScrollPane(view);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setOpaque(true);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }

    /**
     * Standardizes the 18pt bold title + muted subtitle header block.
     */
    static String formatWrappingHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.regionMatches(true, 0, "<html>", 0, 6)) {
            return text;
        }
        return "<html>" + SwingHtmlUtils.escapeHtml(text) + "</html>";
    }

    static JPanel createPageHeader(JLabel titleLabel, JLabel subtitleLabel, @Nullable JComponent rightComponent) {
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        subtitleLabel.setText(formatWrappingHtml(subtitleLabel.getText()));
        subtitleLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        JPanel titleBlock = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2", "[grow, fill]", "[]"));
        titleBlock.setOpaque(false);
        titleBlock.add(titleLabel, GROWX_WMIN_0);
        titleBlock.add(subtitleLabel, GROWX_WMIN_0);

        if (rightComponent == null) {
            return titleBlock;
        }

        JPanel header = new JPanel(new MigLayout("fillx, insets 0", "[0:0, grow, fill][]", ROW_CENTER));
        header.setOpaque(false);
        header.add(titleBlock, GROWX_WMIN_0);
        header.add(rightComponent);
        return header;
    }

    /**
     * Applies the standard rounded card style using FlatLaf style expressions.
     */
    static Color getCardBorderColor() {
        Color borderFallback;
        if (FlatLaf.isLafDark()) {
            borderFallback = new Color(0x3c, 0x3f, 0x41);
        } else {
            borderFallback = new Color(0xd8, 0xdc, 0xe0);
        }
        return SwingHtmlUtils.getUiColor(borderFallback, "Component.borderColor");
    }

    static String getCardBorderHex() {
        return SwingHtmlUtils.toHex(getCardBorderColor());
    }

    static String getPillBackgroundDirective() {
        if (FlatLaf.isLafDark()) {
            return "lighten($Panel.background, 7%)";
        }
        return "darken($Panel.background, 5%)";
    }

    static JLabel createSectionHeader(String title, Codicons icon, String fontStyle) {
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE, fontStyle);
        label.setIconTextGap(8);
        label.setIcon(FontIcon.of(icon, 18, SwingHtmlUtils.getAccentColor()));
        return label;
    }

    static Color resolveInfoIconColor() {
        if (FlatLaf.isLafDark()) {
            return INFO_BLUE_DARK;
        }
        return INFO_BLUE_LIGHT;
    }

    static JLabel createInfoIcon(String htmlTooltip) {
        ToolTipManager toolTipManager = ToolTipManager.sharedInstance();
        if (toolTipManager.getDismissDelay() < INFO_TOOLTIP_DISMISS_DELAY_MS) {
            toolTipManager.setDismissDelay(INFO_TOOLTIP_DISMISS_DELAY_MS);
        }
        JLabel label = new JLabel();
        label.setToolTipText(htmlTooltip);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showInfoTooltipImmediately(label, e);
            }
        });
        updateInfoIconTheme(label);
        return label;
    }

    static void showInfoTooltipImmediately(JComponent component, @Nullable MouseEvent event) {
        if (component == null) {
            return;
        }
        ToolTipManager toolTipManager = ToolTipManager.sharedInstance();
        int originalInitialDelay = toolTipManager.getInitialDelay();
        MouseEvent targetEvent = event;
        if (targetEvent == null) {
            targetEvent = new MouseEvent(component, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                    Math.max(1, component.getWidth() / 2), Math.max(1, component.getHeight() / 2), 0, false);
        }
        try {
            toolTipManager.setInitialDelay(0);
            toolTipManager.mouseMoved(targetEvent);
        } finally {
            toolTipManager.setInitialDelay(originalInitialDelay);
        }
    }

    static void updateInfoIconTheme(JLabel iconLabel) {
        if (iconLabel == null) {
            return;
        }
        Color infoColor = resolveInfoIconColor();
        iconLabel.setIcon(FontIcon.of(Codicons.INFO, 16, infoColor));
        iconLabel.putClientProperty(FlatClientProperties.STYLE, null);
        iconLabel.setOpaque(false);
    }

    static JPanel createModelControlBox(JLabel badgeLabel, JComboBox<GeminiModelConfig> comboBox,
            String comboConstraints) {
        JPanel box = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", ROW_CENTER));
        box.setOpaque(false);
        box.add(badgeLabel, "hidemode 3");
        box.add(comboBox, comboConstraints);
        return box;
    }

    static void styleCardPanel(JPanel card) {
        String borderHex = getCardBorderHex();
        String cardBgDirective;
        if (FlatLaf.isLafDark()) {
            cardBgDirective = "lighten($Panel.background, 3%)";
        } else {
            cardBgDirective = "darken($Panel.background, 2%)";
        }

        card.setOpaque(true);
        card.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; border: 1,1,1,1," + borderHex + ",1,12; background: " + cardBgDirective);
    }

    /**
     * Builds the bold title + optional badge + muted description block.
     */
    static JPanel createFieldTextBlock(String title, String description, @Nullable JLabel badge) {
        JPanel block = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2", "[grow, fill]", ""));
        block.setOpaque(false);

        JLabel titleLbl = new JLabel(title);
        titleLbl.putClientProperty(FlatClientProperties.STYLE, "font: bold");

        if (badge != null) {
            JPanel titleLine = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", ROW_CENTER));
            titleLine.setOpaque(false);
            titleLine.add(titleLbl);
            titleLine.add(badge);
            block.add(titleLine, GROWX_WMIN_0);
        } else {
            block.add(titleLbl, GROWX_WMIN_0);
        }

        JLabel descLbl = new JLabel(formatWrappingHtml(description));
        descLbl.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        block.add(descLbl, GROWX_WMIN_0);

        return block;
    }

    /**
     * Combines a field text block with a control component in a row.
     */
    static JPanel createFieldRow(JPanel fieldBlock, JComponent control, String controlConstraints) {
        JPanel row = new JPanel(new MigLayout("fillx, insets 0", "[0:0, grow, fill]16[right]", ROW_CENTER));
        row.setOpaque(false);
        row.add(fieldBlock, GROWX_WMIN_0);
        row.add(control, controlConstraints);
        return row;
    }

    /**
     * Shared factory for non-editable, line-wrapping, non-focusable, transparent JTextArea.
     */
    static JTextArea createReadOnlyTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont != null) {
            area.setFont(baseFont.deriveFont(Math.max(11f, baseFont.getSize2D() - 1f)));
        }
        area.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return area;
    }

    /**
     * Styles a label as a monospaced pill badge with a code icon.
     */
    static void styleCodePillBadge(JLabel badge) {
        String borderHex = getCardBorderHex();
        String pillBg = getPillBackgroundDirective();

        badge.setIcon(FontIcon.of(Codicons.CODE, 13, SwingHtmlUtils.getAccentColor()));
        badge.setIconTextGap(5);
        badge.putClientProperty(FlatClientProperties.STYLE,
                "font: $monospaced.font -1; arc: 8; border: 2,8,2,8," + borderHex + ",1,8; background: " + pillBg);
    }

    /**
     * Selects a GeminiModelConfig item in a JComboBox matching a target apiName.
     */
    static void selectModelByApiName(JComboBox<GeminiModelConfig> combo, String apiName) {
        String target = StringUtils.defaultString(apiName).trim();
        for (int i = 0; i < combo.getItemCount(); i++) {
            GeminiModelConfig item = combo.getItemAt(i);
            if (item != null && target.equals(item.apiName())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    static void selectThinkingLevelOptionById(JComboBox<ThinkingLevelOption> combo, String id) {
        String target = StringUtils.defaultString(id).trim();
        for (int i = 0; i < combo.getItemCount(); i++) {
            ThinkingLevelOption item = combo.getItemAt(i);
            if (item != null && target.equalsIgnoreCase(item.id())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    /**
     * Updates model badge and description area based on combo selection.
     */
    static void updateModelSelectionDetails(JComboBox<GeminiModelConfig> combo, JLabel badge, JTextArea descArea,
            @Nullable String fallbackApiName) {
        GeminiModelConfig selected = (GeminiModelConfig) combo.getSelectedItem();
        if (selected == null) {
            badge.setText("");
            badge.setVisible(false);
            descArea.setText("");
            descArea.setVisible(false);
            return;
        }

        String apiName = StringUtils.defaultString(selected.apiName());
        if (apiName.isBlank() && fallbackApiName != null) {
            badge.setText(fallbackApiName);
            badge.setVisible(!fallbackApiName.isBlank());
        } else {
            badge.setText(apiName);
            badge.setVisible(!apiName.isBlank());
        }

        String description = StringUtils.defaultString(selected.description());
        descArea.setText(description);
        descArea.setVisible(!description.isBlank());
    }

    static JTabbedPane createUnderlinedTabbedPane(JComponent trailingComponent) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, trailingComponent);
        return tabs;
    }

    static void configureSyntaxTextArea(RSyntaxTextArea area, String syntaxStyle, boolean editable, boolean lineWrap) {
        area.setEditable(editable);
        area.setSyntaxEditingStyle(syntaxStyle);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setLineWrap(lineWrap);
        area.setWrapStyleWord(lineWrap);
    }

    static Timer createStatusTimer(@Nullable JLabel statusLabel, int delayMs) {
        Timer timer = new Timer(delayMs, e -> {
            if (statusLabel != null) {
                statusLabel.setText("");
                statusLabel.setToolTipText(null);
            }
        });
        timer.setRepeats(false);
        return timer;
    }

    static void showStatusMessage(JLabel statusLabel, @Nullable Timer statusTimer, String message, boolean success) {
        if (statusLabel == null) {
            return;
        }
        Color statusColor;
        if (success) {
            statusColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
            statusLabel.setToolTipText(null);
        } else {
            statusColor = SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");
            statusLabel.setToolTipText(message);
        }
        statusLabel.setForeground(statusColor);
        statusLabel.setText(message);
        if (statusTimer != null) {
            statusTimer.restart();
        }
    }

    static void stopTimer(@Nullable Timer timer) {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    static int resolveMenuShortcutMask() {
        if (GraphicsEnvironment.isHeadless()) {
            return InputEvent.CTRL_DOWN_MASK;
        }
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    static void selectTabSafely(@Nullable JTabbedPane tabbedPane, int tabIndex) {
        if (tabbedPane != null && tabIndex >= 0 && tabbedPane.getTabCount() > tabIndex) {
            tabbedPane.setSelectedIndex(tabIndex);
        }
    }

    static void clearStatusMessage(@Nullable JLabel statusLabel, @Nullable Timer statusTimer, boolean hideLabel) {
        stopTimer(statusTimer);
        if (statusLabel == null) {
            return;
        }
        statusLabel.setText("");
        statusLabel.setToolTipText(null);
        if (hideLabel) {
            statusLabel.setVisible(false);
        }
    }

    static Color resolveActionButtonIconColor(@Nullable String style) {
        if (style != null && style.contains("foreground: #ffffff")) {
            return Color.WHITE;
        }
        if (style != null && style.contains("$Actions.Red")) {
            return SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");
        }
        if (style != null && style.contains("$Actions.Green")) {
            return SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green");
        }
        return SwingHtmlUtils.getUiColor(Color.DARK_GRAY, "Button.foreground", "Label.foreground");
    }

    static JButton createActionButton(String text, @Nullable Codicons icon, @Nullable Insets margin, boolean borderless,
            @Nullable String style, @Nullable ActionListener listener) {
        FontIcon fontIcon = null;
        if (icon != null) {
            if (borderless) {
                fontIcon = FontIcon.of(icon, 16);
            } else {
                fontIcon = FontIcon.of(icon, 16, resolveActionButtonIconColor(style));
            }
        }
        JButton btn = new JButton(text, fontIcon);
        if (margin != null) {
            btn.setMargin(margin);
        }
        if (borderless) {
            btn.setFocusable(false);
            btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        }
        if (style != null) {
            btn.putClientProperty(FlatClientProperties.STYLE, style);
        }
        if (listener != null) {
            btn.addActionListener(listener);
        }
        return btn;
    }

    static void refreshActionButtonIcons(@Nullable JComponent container) {
        if (container == null) {
            return;
        }
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && button.getIcon() instanceof FontIcon fontIcon) {
                boolean borderless = FlatClientProperties.BUTTON_TYPE_BORDERLESS
                        .equals(button.getClientProperty(FlatClientProperties.BUTTON_TYPE));
                if (!borderless) {
                    Object rawStyle = button.getClientProperty(FlatClientProperties.STYLE);
                    String style = null;
                    if (rawStyle instanceof String s) {
                        style = s;
                    }
                    button.setIcon(FontIcon.of(fontIcon.getIkon(), fontIcon.getIconSize(),
                            resolveActionButtonIconColor(style)));
                }
            } else if (child instanceof JComponent childContainer) {
                refreshActionButtonIcons(childContainer);
            }
        }
    }

    static void updateSelectedTabIcons(JTabbedPane tabbedPane, List<Codicons> icons) {
        if (tabbedPane == null || icons == null || tabbedPane.getTabCount() != icons.size()) {
            return;
        }
        Color accent = SwingHtmlUtils.getAccentColor();
        Color muted = SwingHtmlUtils.getMutedForegroundColor();
        int selectedIndex = tabbedPane.getSelectedIndex();
        for (int i = 0; i < icons.size(); i++) {
            Color color;
            if (i == selectedIndex) {
                color = accent;
            } else {
                color = muted;
            }
            tabbedPane.setIconAt(i, FontIcon.of(icons.get(i), 15, color));
        }
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    static final class UiGuard {
        private boolean active;

        boolean isActive() {
            return active;
        }

        void run(Runnable action) {
            boolean previous = active;
            active = true;
            try {
                action.run();
            } finally {
                active = previous;
            }
        }

        <T> T supply(Supplier<T> supplier) {
            boolean previous = active;
            active = true;
            try {
                return supplier.get();
            } finally {
                active = previous;
            }
        }
    }

    static final class InlineConfirmBar extends JPanel {
        private static final long serialVersionUID = 1L;
        final JLabel promptLabel;
        final JButton confirmButton;
        final JButton cancelButton;
        private transient Runnable onConfirm;

        InlineConfirmBar() {
            super(new MigLayout("insets 0, gapx 6", "[][][]", ROW_CENTER));
            setOpaque(false);
            this.promptLabel = new JLabel();
            this.promptLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold");
            this.confirmButton = new JButton("Confirm");
            this.confirmButton.addActionListener(e -> confirm());
            this.cancelButton = new JButton("Cancel");
            this.cancelButton.addActionListener(e -> cancel());

            add(this.promptLabel);
            add(this.confirmButton);
            add(this.cancelButton);
            setVisible(false);
        }

        void prompt(String message, String confirmText, boolean danger, @Nullable Runnable onConfirmAction) {
            this.onConfirm = onConfirmAction;
            this.promptLabel.setText(StringUtils.defaultString(message));
            Color promptColor;
            String buttonStyle;
            if (danger) {
                promptColor = SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");
                buttonStyle = RED_BUTTON_STYLE;
            } else {
                promptColor = SwingHtmlUtils.getWarningColor();
                buttonStyle = ACCENT_BUTTON_STYLE;
            }
            this.promptLabel.setForeground(promptColor);
            this.confirmButton.setText(StringUtils.defaultIfBlank(confirmText, "Confirm"));
            this.confirmButton.putClientProperty(FlatClientProperties.STYLE, buttonStyle);
            setVisible(true);
            revalidate();
            repaint();
        }

        @SuppressWarnings("PMD.NullAssignment")
        void confirm() {
            Runnable action = this.onConfirm;
            this.onConfirm = null;
            setVisible(false);
            revalidate();
            repaint();
            if (action != null) {
                action.run();
            }
        }

        @SuppressWarnings("PMD.NullAssignment")
        void cancel() {
            this.onConfirm = null;
            if (isVisible()) {
                setVisible(false);
                revalidate();
                repaint();
            }
        }

        boolean isPromptVisible() {
            return isVisible();
        }

        String getMessage() {
            return promptLabel.getText();
        }
    }

    @Nullable
    static File chooseDirectory(Component parent, String dialogTitle, @Nullable Path initialDirectory) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
        if (StringUtils.isNotBlank(dialogTitle)) {
            chooser.setDialogTitle(dialogTitle);
        }
        if (initialDirectory != null) {
            chooser.setCurrentDirectory(initialDirectory.toFile());
        }
        if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    @Nullable
    static File chooseOpenFile(Component parent, String dialogTitle,
            @Nullable SystemFileChooser.FileFilter fileFilter) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        SystemFileChooser chooser = new SystemFileChooser();
        if (StringUtils.isNotBlank(dialogTitle)) {
            chooser.setDialogTitle(dialogTitle);
        }
        if (fileFilter != null) {
            chooser.setFileFilter(fileFilter);
        }
        if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    static final class WarningBanner extends JPanel {
        private static final long serialVersionUID = 1L;
        final JLabel iconLabel;
        final JLabel messageLabel;

        WarningBanner() {
            this("");
        }

        WarningBanner(String initialMessage) {
            super(new MigLayout("fillx, insets 0", "[][0:0, grow, fill]", "[]"));
            this.iconLabel = new JLabel();
            this.messageLabel = new JLabel(formatWrappingHtml(initialMessage));
            add(iconLabel, "gapright 8");
            add(messageLabel, GROWX_WMIN_0);
            setVisible(false);
        }

        void setVisibleState(boolean visible, String messageText) {
            if (visible) {
                Color warnColor = SwingHtmlUtils.getWarningColor();
                iconLabel.setIcon(FontIcon.of(Codicons.WARNING, 18, warnColor));
                messageLabel.setText(formatWrappingHtml(messageText));
                SwingHtmlUtils.applyTintedBannerStyle(this, warnColor, 10, 14, 14);
            }
            setVisible(visible);
            revalidate();
            repaint();
        }
    }

    static <T> void replaceListItems(DefaultListModel<T> model, @Nullable List<T> items) {
        model.clear();
        if (items == null) {
            return;
        }
        for (T item : items) {
            model.addElement(item);
        }
    }

    static <T, K> int replaceListItemsPreservingSelection(DefaultListModel<T> model, JList<T> list,
            @Nullable List<T> items, Function<T, K> keyExtractor, @Nullable K keyToSelect,
            boolean selectFirstIfUnmatched) {
        model.clear();
        if (items == null || items.isEmpty()) {
            return -1;
        }
        int matchedIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            model.addElement(item);
            if (keyToSelect != null && keyToSelect.equals(keyExtractor.apply(item))) {
                matchedIndex = i;
            }
        }
        int selectedIndex = matchedIndex;
        if (selectedIndex < 0 && selectFirstIfUnmatched) {
            selectedIndex = 0;
        }
        if (selectedIndex >= 0) {
            list.setSelectedIndex(selectedIndex);
        }
        return selectedIndex;
    }

    record ListCellColors(Color background, Color foreground, Color muted, Color iconColor) {
    }

    static ListCellColors resolveListCellColors(JList<?> list, boolean isSelected) {
        if (isSelected) {
            Color bg = SwingHtmlUtils.getUiColor(list.getSelectionBackground(), "List.selectionBackground");
            Color fg = SwingHtmlUtils.getUiColor(list.getSelectionForeground(), "List.selectionForeground");
            return new ListCellColors(bg, fg, fg, fg);
        }
        return new ListCellColors(SwingHtmlUtils.getUiColor(list.getBackground(), "List.background"),
                SwingHtmlUtils.getUiColor(list.getForeground(), "Label.foreground"),
                SwingHtmlUtils.getMutedForegroundColor(), SwingHtmlUtils.getAccentColor());
    }

    private static final class ScrollableCardsPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        ScrollableCardsPanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - 32, 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
