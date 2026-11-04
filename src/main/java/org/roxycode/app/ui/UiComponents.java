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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import java.awt.event.KeyEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.function.Consumer;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
    static final String RED_BUTTON_STYLE = "background: $Actions.Red; focusedBackground: $Actions.Red; hoverBackground: darken($Actions.Red, 8%); pressedBackground: darken($Actions.Red, 14%); foreground: #ffffff";
    static final Color AMBER_LIGHT = new Color(0xd9, 0x77, 0x06);
    static final Color AMBER_DARK = new Color(0xf5, 0x9e, 0x0b);

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
     * Creates a rounded image panel from a classpath resource path.
     */
    static JPanel createRoundedImagePanel(String resourcePath, int size, int arc) {
        return createRoundedImagePanel(loadClasspathImage(resourcePath), size, arc);
    }

    /**
     * Creates a rounded image panel that clips and renders the image directly.
     */
    static JPanel createRoundedImagePanel(@Nullable Image image, int size, int arc) {
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
                    if (image != null) {
                        Shape oldClip = g2.getClip();
                        g2.clip(clipShape);
                        g2.drawImage(image, 0, 0, w, h, null);
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
        JButton button = new JButton(text, FontIcon.of(Codicons.CLIPPY, 14));
        button.setEnabled(enabled);
        button.addActionListener(e -> onCopy.run());
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
        return SwingHtmlUtils.showCopyButtonFeedback(button, existingTimer, "Copied!", FontIcon.of(Codicons.CHECK, 14),
                originalText, FontIcon.of(Codicons.CLIPPY, 14), delayMs);
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
    static JPanel createPageHeader(JLabel titleLabel, JLabel subtitleLabel, @Nullable JComponent rightComponent) {
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
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
            return AMBER_DARK;
        }
        return AMBER_LIGHT;
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
        iconLabel.setIcon(FontIcon.of(Codicons.INFO, 15, infoColor));
        iconLabel.putClientProperty(FlatClientProperties.STYLE, null);
        iconLabel.setBorder(null);
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

        JLabel descLbl = new JLabel(description);
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

    private static final String ACTIVE_CONFIRM_OVERLAY_KEY = "roxycode.activeConfirmOverlay";

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

    static JButton createActionButton(String text, @Nullable Codicons icon, @Nullable Insets margin, boolean borderless,
            @Nullable String style, @Nullable ActionListener listener) {
        FontIcon fontIcon = null;
        if (icon != null) {
            if (borderless) {
                fontIcon = FontIcon.of(icon, 16);
            } else {
                fontIcon = FontIcon.of(icon, 16, Color.WHITE);
            }
        }
        JButton btn = new JButton(text, fontIcon);
        if (margin != null) {
            btn.setMargin(margin);
        }
        if (borderless) {
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

    static void updateTabIcons(JTabbedPane tabbedPane, List<Codicons> icons, List<Color> colors) {
        if (tabbedPane == null || icons == null || colors == null) {
            return;
        }
        int count = Math.min(tabbedPane.getTabCount(), Math.min(icons.size(), colors.size()));
        for (int i = 0; i < count; i++) {
            tabbedPane.setIconAt(i, FontIcon.of(icons.get(i), 15, colors.get(i)));
        }
    }

    static ConfirmOverlay showConfirmOverlay(JComponent hostComponent, String title, String message,
            String confirmButtonText, boolean dangerAction, Runnable onConfirm) {
        return showConfirmOverlay(hostComponent, title, message, confirmButtonText, dangerAction, confirmed -> {
            if (Boolean.TRUE.equals(confirmed) && onConfirm != null) {
                onConfirm.run();
            }
        });
    }

    static ConfirmOverlay showConfirmOverlay(JComponent hostComponent, String title, String message,
            String confirmButtonText, boolean dangerAction, Consumer<Boolean> onResult) {
        if (hostComponent != null) {
            ConfirmOverlay existing = getActiveConfirmOverlay(hostComponent);
            if (existing != null) {
                existing.dismiss();
            }
        }
        ConfirmOverlay overlay = new ConfirmOverlay(hostComponent, title, message, confirmButtonText, dangerAction,
                onResult);
        overlay.attach();
        return overlay;
    }

    @Nullable
    static ConfirmOverlay getActiveConfirmOverlay(@Nullable JComponent hostComponent) {
        if (hostComponent == null) {
            return null;
        }
        Object prop = hostComponent.getClientProperty(ACTIVE_CONFIRM_OVERLAY_KEY);
        if (prop instanceof ConfirmOverlay overlay && overlay.isVisible()) {
            return overlay;
        }
        JRootPane rootPane = SwingUtilities.getRootPane(hostComponent);
        if (rootPane != null && rootPane.getGlassPane() instanceof ConfirmOverlay glassOverlay
                && glassOverlay.isVisible()) {
            return glassOverlay;
        }
        return null;
    }

    static final class ConfirmOverlay extends JPanel {
        private static final long serialVersionUID = 1L;

        private final transient JComponent hostComponent;
        private final transient Consumer<Boolean> onResult;
        final JPanel cardPanel;
        final JLabel iconLabel;
        final JLabel titleLabel;
        final JTextArea messageArea;
        final JButton cancelButton;
        final JButton confirmButton;
        private transient JRootPane attachedRootPane;
        private transient Component previousGlassPane;
        private boolean previousGlassPaneVisible;
        private boolean closed;

        ConfirmOverlay(@Nullable JComponent hostComponent, String title, String message, String confirmButtonText,
                boolean dangerAction, @Nullable Consumer<Boolean> onResult) {
            super(new MigLayout("fill, ins 24", "[center]", ROW_CENTER));
            this.hostComponent = hostComponent;
            this.onResult = onResult;
            setOpaque(false);
            setFocusCycleRoot(true);

            cardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 18 20 16 20, gapy 10", "[grow, fill]", "[]"));
            styleCardPanel(cardPanel);

            Color iconColor;
            if (dangerAction) {
                iconColor = SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");
            } else {
                iconColor = SwingHtmlUtils.getWarningColor();
            }
            iconLabel = new JLabel(FontIcon.of(Codicons.WARNING, 20, iconColor));
            titleLabel = new JLabel(StringUtils.defaultString(title));
            titleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");

            JPanel headerRow = new JPanel(new MigLayout("insets 0, gapx 10", "[][grow]", ROW_CENTER));
            headerRow.setOpaque(false);
            headerRow.add(iconLabel);
            headerRow.add(titleLabel, GROWX_WMIN_0);
            cardPanel.add(headerRow, GROWX_WMIN_0);

            messageArea = createReadOnlyTextArea();
            messageArea.setText(StringUtils.defaultString(message));
            messageArea.setForeground(SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Label.foreground"));
            cardPanel.add(messageArea, GROWX_WMIN_0);

            JPanel footerRow = new JPanel(new MigLayout("insets 4 0 0 0, gapx 8", "push[][]", ROW_CENTER));
            footerRow.setOpaque(false);

            cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> cancel());

            confirmButton = new JButton(StringUtils.defaultIfBlank(confirmButtonText, "Confirm"));
            Color actionBg;
            if (dangerAction) {
                actionBg = iconColor;
            } else {
                actionBg = SwingHtmlUtils.getAccentColor();
            }
            confirmButton.setForeground(Color.WHITE);
            confirmButton.setBackground(actionBg);
            confirmButton.putClientProperty(FlatClientProperties.STYLE, "font: bold; borderWidth: 0; focusWidth: 0");
            confirmButton.addActionListener(e -> confirm());

            footerRow.add(cancelButton);
            footerRow.add(confirmButton);
            cardPanel.add(footerRow, GROWX_WMIN_0);

            cardPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    e.consume();
                }
            });

            add(cardPanel, "w 380:420:460");

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!cardPanel.getBounds().contains(e.getPoint())) {
                        cancel();
                    }
                    e.consume();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    e.consume();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    e.consume();
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
            });
            addMouseWheelListener(e -> {
            });

            registerKeyboardAction(e -> cancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            registerKeyboardAction(e -> cancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        }

        void attach() {
            if (hostComponent != null) {
                hostComponent.putClientProperty(ACTIVE_CONFIRM_OVERLAY_KEY, this);
                attachedRootPane = SwingUtilities.getRootPane(hostComponent);
            }
            if (attachedRootPane != null) {
                previousGlassPane = attachedRootPane.getGlassPane();
                previousGlassPaneVisible = previousGlassPane != null && previousGlassPane.isVisible();
                attachedRootPane.setGlassPane(this);
                setVisible(true);
                attachedRootPane.revalidate();
                attachedRootPane.repaint();
            } else {
                setVisible(true);
            }
            confirmButton.requestFocusInWindow();
        }

        void confirm() {
            if (closed) {
                return;
            }
            closed = true;
            dismiss();
            if (onResult != null) {
                onResult.accept(Boolean.TRUE);
            }
        }

        void cancel() {
            if (closed) {
                return;
            }
            closed = true;
            dismiss();
            if (onResult != null) {
                onResult.accept(Boolean.FALSE);
            }
        }

        @SuppressWarnings("PMD.NullAssignment")
        void dismiss() {
            setVisible(false);
            if (hostComponent != null && equals(hostComponent.getClientProperty(ACTIVE_CONFIRM_OVERLAY_KEY))) {
                hostComponent.putClientProperty(ACTIVE_CONFIRM_OVERLAY_KEY, null);
            }
            if (attachedRootPane != null) {
                if (previousGlassPane != null && equals(attachedRootPane.getGlassPane())) {
                    attachedRootPane.setGlassPane(previousGlassPane);
                    previousGlassPane.setVisible(previousGlassPaneVisible);
                }
                attachedRootPane.revalidate();
                attachedRootPane.repaint();
                attachedRootPane = null;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Color scrimColor;
                if (FlatLaf.isLafDark()) {
                    scrimColor = new Color(0, 0, 0, 120);
                } else {
                    scrimColor = new Color(15, 23, 42, 95);
                }
                g2.setColor(scrimColor);
                g2.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
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
            super(new MigLayout("fillx, insets 0", "[][grow]", "[]"));
            this.iconLabel = new JLabel();
            this.messageLabel = new JLabel(initialMessage);
            add(iconLabel, "gapright 8");
            add(messageLabel, "growx, wmin 100");
            setVisible(false);
        }

        void setVisibleState(boolean visible, String messageText) {
            if (visible) {
                Color warnColor = SwingHtmlUtils.getWarningColor();
                iconLabel.setIcon(FontIcon.of(Codicons.WARNING, 18, warnColor));
                messageLabel.setText(messageText);
                SwingHtmlUtils.applyTintedBannerStyle(this, warnColor, 10, 14, 14);
            }
            setVisible(visible);
            revalidate();
            repaint();
        }
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
