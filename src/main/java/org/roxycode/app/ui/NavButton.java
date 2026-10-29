package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

public final class NavButton extends JButton {
    private static final long serialVersionUID = 1L;
    private static final int VERTICAL_PADDING = 8;
    private static final int HORIZONTAL_PADDING = 12;
    private static final int STATUS_ICON_GAP = 8;
    private static final int ROW_ARC = 8;
    private static final int ACCENT_PILL_WIDTH = 3;
    private static final int BADGE_SIZE = 18;
    private static final Color AMBER_LIGHT = new Color(0xd9, 0x77, 0x06);
    private static final Color AMBER_DARK = new Color(0xf5, 0x9e, 0x0b);
    private static final Color AMBER_TINT_LIGHT = new Color(245, 158, 11, 30);
    private static final Color AMBER_TINT_DARK = new Color(245, 158, 11, 45);

    static final String BUSY_TOOLTIP = "Agent is working in the background...";
    static final String WAITING_APPROVAL_TOOLTIP = "Waiting for your approval to continue";

    private final transient HourglassAnimator hourglassAnimator = new HourglassAnimator(this::repaint);
    private boolean busy;
    private boolean waitingApproval;
    private transient FontIcon statusIcon;

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

    public void setBusy(boolean busy) {
        if (this.busy != busy) {
            this.busy = busy;
            updateStatusIcon();
        }
    }

    public boolean isBusy() {
        return busy;
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

    FontIcon getStatusIcon() {
        return statusIcon;
    }

    Timer getBusyTimer() {
        return hourglassAnimator.getTimer();
    }

    void advanceBusyFrame() {
        if (!busy || statusIcon == null) {
            hourglassAnimator.stop();
            return;
        }
        hourglassAnimator.advanceFrame();
    }

    @Override
    public void removeNotify() {
        hourglassAnimator.stop();
        super.removeNotify();
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void updateStatusIcon() {
        if (busy) {
            statusIcon = hourglassAnimator.start();
            setToolTipText(BUSY_TOOLTIP);
        } else if (waitingApproval) {
            hourglassAnimator.stop();
            statusIcon = FontIcon.of(Codicons.BELL_DOT, 14);
            setToolTipText(WAITING_APPROVAL_TOOLTIP);
        } else {
            hourglassAnimator.stop();
            statusIcon = null;
            setToolTipText(null);
        }
        updatePaddingBorder();
        revalidate();
        repaint();
    }

    private void updatePaddingBorder() {
        int rightPadding = statusIcon != null
                ? HORIZONTAL_PADDING + statusIcon.getIconWidth() + STATUS_ICON_GAP
                : HORIZONTAL_PADDING;
        setBorder(
                BorderFactory.createEmptyBorder(VERTICAL_PADDING, HORIZONTAL_PADDING, VERTICAL_PADDING, rightPadding));
    }

    @Override
    public void setSelected(boolean b) {
        super.setSelected(b);
        if (b) {
            putClientProperty(FlatClientProperties.STYLE,
                    FlatLaf.isLafDark()
                            ? "background: $Component.accentColor; foreground: $Component.accentForeground"
                            : "background: #0d6efd; foreground: #ffffff");
            setContentAreaFilled(true);
        } else {
            putClientProperty(FlatClientProperties.STYLE, "");
            setContentAreaFilled(false);
        }
        updateIconColor();
    }

    private void updateIconColor() {
        if (getIcon() instanceof FontIcon fontIcon && getForeground() != null) {
            fontIcon.setIconColor(getForeground());
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
        boolean highlightWaiting = !isSelected() && !busy && waitingApproval;
        boolean dark = FlatLaf.isLafDark();
        Color amberAccent = dark ? AMBER_DARK : AMBER_LIGHT;

        if (highlightWaiting && g instanceof Graphics2D) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(dark ? AMBER_TINT_DARK : AMBER_TINT_LIGHT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), ROW_ARC, ROW_ARC);
                g2.setColor(amberAccent);
                g2.fillRoundRect(0, 2, ACCENT_PILL_WIDTH, Math.max(0, getHeight() - 4), ACCENT_PILL_WIDTH,
                        ACCENT_PILL_WIDTH);
            } finally {
                g2.dispose();
            }
        }

        super.paintComponent(g);

        if (statusIcon != null) {
            int x = getWidth() - statusIcon.getIconWidth() - HORIZONTAL_PADDING;
            int y = (getHeight() - statusIcon.getIconHeight()) / 2;

            if (isSelected()) {
                if (getForeground() != null)
                    statusIcon.setIconColor(getForeground());
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
                if (color != null)
                    statusIcon.setIconColor(color);
            }
            statusIcon.paintIcon(this, g, x, y);
        }
    }
}
