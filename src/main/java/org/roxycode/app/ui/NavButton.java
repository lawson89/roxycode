package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

final class NavButton extends JButton {
    private static final long serialVersionUID = 1L;
    private static final int VERTICAL_PADDING = 8;
    private static final int HORIZONTAL_PADDING = 12;
    private static final int STATUS_ICON_GAP = 8;
    private static final int ROW_ARC = 8;
    private static final int ACCENT_PILL_WIDTH = 3;
    private static final int BADGE_SIZE = 18;
    static final int BUSY_DOT_SIZE = 6;
    private static final Color AMBER_TINT_LIGHT = new Color(245, 158, 11, 30);
    private static final Color AMBER_TINT_DARK = new Color(245, 158, 11, 45);

    static final String WAITING_APPROVAL_TOOLTIP = "Waiting for your approval to continue";
    static final String BUSY_TOOLTIP = "Agent is working in the background...";

    private boolean waitingApproval;
    private boolean busy;
    private String attentionTooltip = WAITING_APPROVAL_TOOLTIP;
    transient FontIcon statusIcon;

    public NavButton(String text, Icon icon) {
        super(text, icon);
        setHorizontalAlignment(SwingConstants.LEFT);
        setFocusPainted(false);
        setContentAreaFilled(false);
        updatePaddingBorder();
        putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        putClientProperty("JButton.arc", ROW_ARC);
        setIconTextGap(10);
    }

    public void setWaitingApproval(boolean waitingApproval) {
        if (this.waitingApproval != waitingApproval) {
            this.waitingApproval = waitingApproval;
            updateStatusIcon();
        }
    }

    public boolean isWaitingApproval() {
        return waitingApproval;
    }

    public void setBusy(boolean busy) {
        if (this.busy != busy) {
            this.busy = busy;
            updateStatusIcon();
        }
    }

    public boolean isBusy() {
        return busy;
    }

    public void setAttentionTooltip(String tooltip) {
        if (tooltip == null || tooltip.isBlank()) {
            this.attentionTooltip = WAITING_APPROVAL_TOOLTIP;
        } else {
            this.attentionTooltip = tooltip;
        }
        if (waitingApproval) {
            setToolTipText(this.attentionTooltip);
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void updateStatusIcon() {
        if (waitingApproval) {
            statusIcon = FontIcon.of(Codicons.BELL_DOT, 14);
            setToolTipText(attentionTooltip);
        } else {
            statusIcon = null;
            if (isEnabled()) {
                restoreStatusTooltip();
            }
        }
        updatePaddingBorder();
        revalidate();
        repaint();
    }

    private void updatePaddingBorder() {
        int rightPadding = HORIZONTAL_PADDING;
        if (statusIcon != null) {
            rightPadding = HORIZONTAL_PADDING + statusIcon.getIconWidth() + STATUS_ICON_GAP;
        } else if (busy) {
            rightPadding = HORIZONTAL_PADDING + BUSY_DOT_SIZE + STATUS_ICON_GAP;
        }
        setBorder(
                BorderFactory.createEmptyBorder(VERTICAL_PADDING, HORIZONTAL_PADDING, VERTICAL_PADDING, rightPadding));
    }

    @Override
    public void setSelected(boolean b) {
        super.setSelected(b);
        setContentAreaFilled(false);
        if (b) {
            putClientProperty(FlatClientProperties.STYLE, "font: bold; foreground: $Component.accentColor");
        } else {
            putClientProperty(FlatClientProperties.STYLE, "font: plain; foreground: $Button.foreground");
        }
        updateIconColor();
    }

    void restoreStatusTooltip() {
        if (waitingApproval) {
            setToolTipText(attentionTooltip);
        } else if (busy) {
            setToolTipText(BUSY_TOOLTIP);
        } else {
            setToolTipText(null);
        }
    }

    private void updateIconColor() {
        if (getIcon() instanceof FontIcon fontIcon) {
            Color color = getForeground();
            if (!isEnabled()) {
                color = SwingHtmlUtils.getUiColor(color, "Label.disabledForeground");
            } else if (isSelected()) {
                color = SwingHtmlUtils.getAccentColor();
            }
            if (color != null) {
                fontIcon.setIconColor(color);
            }
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setSelected(isSelected());
    }

    @Override
    protected void paintComponent(Graphics g) {
        updateIconColor();
        boolean highlightWaiting = !isSelected() && waitingApproval;
        boolean dark = FlatLaf.isLafDark();
        Color amberAccent;
        if (dark) {
            amberAccent = UiComponents.AMBER_DARK;
        } else {
            amberAccent = UiComponents.AMBER_LIGHT;
        }
        Color amberTint;
        if (dark) {
            amberTint = AMBER_TINT_DARK;
        } else {
            amberTint = AMBER_TINT_LIGHT;
        }

        paintSelectionOrAttentionHighlight(g, dark, highlightWaiting, amberAccent, amberTint);
        super.paintComponent(g);
        paintStatusBadge(g, highlightWaiting, amberAccent);
    }

    private void paintSelectionOrAttentionHighlight(Graphics g, boolean dark, boolean highlightWaiting,
            Color amberAccent, Color amberTint) {
        if (!(g instanceof Graphics2D)) {
            return;
        }
        if (isSelected()) {
            Color accentColor = SwingHtmlUtils.getAccentColor();
            double tintRatio = 0.12;
            if (dark) {
                tintRatio = 0.18;
            }
            paintRowHighlightAndPill(g, SwingHtmlUtils.getTintedBackground(accentColor, tintRatio), accentColor);
        } else if (highlightWaiting) {
            paintRowHighlightAndPill(g, amberTint, amberAccent);
        }
    }

    private void paintRowHighlightAndPill(Graphics g, Color fillTint, Color pillColor) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fillTint);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), ROW_ARC, ROW_ARC);
            g2.setColor(pillColor);
            g2.fillRoundRect(0, 2, ACCENT_PILL_WIDTH, Math.max(0, getHeight() - 4), ACCENT_PILL_WIDTH,
                    ACCENT_PILL_WIDTH);
        } finally {
            g2.dispose();
        }
    }

    private void paintStatusBadge(Graphics g, boolean highlightWaiting, Color amberAccent) {
        if (statusIcon == null) {
            if (busy && g instanceof Graphics2D) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(SwingHtmlUtils.getAccentColor());
                    int dotX = getWidth() - BUSY_DOT_SIZE - HORIZONTAL_PADDING;
                    int dotY = (getHeight() - BUSY_DOT_SIZE) / 2;
                    g2.fillOval(dotX, dotY, BUSY_DOT_SIZE, BUSY_DOT_SIZE);
                } finally {
                    g2.dispose();
                }
            }
            return;
        }
        int x = getWidth() - statusIcon.getIconWidth() - HORIZONTAL_PADDING;
        int y = (getHeight() - statusIcon.getIconHeight()) / 2;

        if (isSelected()) {
            statusIcon.setIconColor(SwingHtmlUtils.getAccentColor());
        } else if (highlightWaiting) {
            if (g instanceof Graphics2D) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(amberAccent);
                    g2.fillOval(x - (BADGE_SIZE - statusIcon.getIconWidth()) / 2, (getHeight() - BADGE_SIZE) / 2,
                            BADGE_SIZE, BADGE_SIZE);
                } finally {
                    g2.dispose();
                }
            }
            statusIcon.setIconColor(Color.WHITE);
        } else {
            Color color = SwingHtmlUtils.getUiColor(getForeground(), "Label.disabledForeground",
                    "Component.accentColor");
            if (color != null) {
                statusIcon.setIconColor(color);
            }
        }
        statusIcon.paintIcon(this, g, x, y);
    }
}
