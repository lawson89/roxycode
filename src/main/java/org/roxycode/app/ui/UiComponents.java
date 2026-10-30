package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.model.GeminiModelConfig;
import org.springframework.lang.Nullable;

/**
 * Shared Swing UI component builders and FlatLaf style helpers to reduce boilerplate.
 */
final class UiComponents {

    private UiComponents() {
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
        titleBlock.add(titleLabel, "growx, wmin 0");
        titleBlock.add(subtitleLabel, "growx, wmin 0");

        if (rightComponent == null) {
            return titleBlock;
        }

        JPanel header = new JPanel(new MigLayout("fillx, insets 0", "[0:0, grow, fill][]", "[center]"));
        header.setOpaque(false);
        header.add(titleBlock, "growx, wmin 0");
        header.add(rightComponent);
        return header;
    }

    /**
     * Applies the standard rounded card style using FlatLaf style expressions.
     */
    static void styleCardPanel(JPanel card) {
        boolean dark = FlatLaf.isLafDark();
        Color borderFallback;
        if (dark) {
            borderFallback = new Color(0x3c, 0x3f, 0x41);
        } else {
            borderFallback = new Color(0xd8, 0xdc, 0xe0);
        }
        String borderHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getUiColor(borderFallback, "Component.borderColor"));

        String cardBgDirective;
        if (dark) {
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
            JPanel titleLine = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", "[center]"));
            titleLine.setOpaque(false);
            titleLine.add(titleLbl);
            titleLine.add(badge);
            block.add(titleLine, "growx, wmin 0");
        } else {
            block.add(titleLbl, "growx, wmin 0");
        }

        JLabel descLbl = new JLabel(description);
        descLbl.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        block.add(descLbl, "growx, wmin 0");

        return block;
    }

    /**
     * Combines a field text block with a control component in a row.
     */
    static JPanel createFieldRow(JPanel fieldBlock, JComponent control, String controlConstraints) {
        JPanel row = new JPanel(new MigLayout("fillx, insets 0", "[0:0, grow, fill]16[right]", "[center]"));
        row.setOpaque(false);
        row.add(fieldBlock, "growx, wmin 0");
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
        boolean dark = FlatLaf.isLafDark();
        Color borderFallback;
        if (dark) {
            borderFallback = new Color(0x3c, 0x3f, 0x41);
        } else {
            borderFallback = new Color(0xd8, 0xdc, 0xe0);
        }
        String borderHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getUiColor(borderFallback, "Component.borderColor"));

        String pillBg;
        if (dark) {
            pillBg = "lighten($Panel.background, 7%)";
        } else {
            pillBg = "darken($Panel.background, 5%)";
        }

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
