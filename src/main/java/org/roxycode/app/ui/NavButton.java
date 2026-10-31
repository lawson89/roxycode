package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

public final class NavButton extends JButton {
    private static final long serialVersionUID = 1L;
    private static final int VERTICAL_PADDING = 8;
    private static final int HORIZONTAL_PADDING = 12;
    private static final int STATUS_ICON_GAP = 8;
    private static final int ROW_ARC = 8;
    private static final int ACCENT_PILL_WIDTH = 3;
    private static final int BADGE_SIZE = 18;
    private static final int BUSY_ICON_SIZE = 13;
    private static final int BUSY_ANIMATION_DELAY_MS = 500;
    private static final FontAwesomeSolid[] HOURGLASS_FRAMES = {FontAwesomeSolid.HOURGLASS_START,
            FontAwesomeSolid.HOURGLASS_HALF, FontAwesomeSolid.HOURGLASS_END};
    private static final Color AMBER_LIGHT = new Color(0xd9, 0x77, 0x06);
    private static final Color AMBER_DARK = new Color(0xf5, 0x9e, 0x0b);
    private static final Color AMBER_TINT_LIGHT = new Color(245, 158, 11, 30);
    private static final Color AMBER_TINT_DARK = new Color(245, 158, 11, 45);

    static final String BUSY_TOOLTIP = "Agent is working in the background...";
    static final String WAITING_APPROVAL_TOOLTIP = "Waiting for your approval to continue";

    private boolean busy;
    private boolean waitingApproval;
    private String attentionTooltip = WAITING_APPROVAL_TOOLTIP;
    private transient FontIcon statusIcon;
    private Timer busyTimer;
    private int busyFrameIndex = 1;

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

    public void setAttentionTooltip(String tooltip) {
        if (tooltip == null || tooltip.isBlank()) {
            this.attentionTooltip = WAITING_APPROVAL_TOOLTIP;
        } else {
            this.attentionTooltip = tooltip;
        }
        if (!busy && waitingApproval) {
            setToolTipText(this.attentionTooltip);
        }
    }

    FontIcon getStatusIcon() {
        return statusIcon;
    }

    Timer getBusyTimer() {
        return busyTimer;
    }

    void advanceBusyFrame() {
        if (!busy || statusIcon == null) {
            stopBusyTimer();
            return;
        }
        busyFrameIndex = (busyFrameIndex + 1) % HOURGLASS_FRAMES.length;
        statusIcon.setIkon(HOURGLASS_FRAMES[busyFrameIndex]);
        repaint();
    }

    private void stopBusyTimer() {
        if (busyTimer != null && busyTimer.isRunning()) {
            busyTimer.stop();
        }
    }

    @Override
    public void removeNotify() {
        stopBusyTimer();
        super.removeNotify();
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void updateStatusIcon() {
        if (busy) {
            busyFrameIndex = 1;
            statusIcon = FontIcon.of(HOURGLASS_FRAMES[busyFrameIndex], BUSY_ICON_SIZE);
            if (busyTimer == null) {
                busyTimer = new Timer(BUSY_ANIMATION_DELAY_MS, e -> advanceBusyFrame());
            }
            if (!busyTimer.isRunning()) {
                busyTimer.start();
            }
            setToolTipText(BUSY_TOOLTIP);
        } else if (waitingApproval) {
            stopBusyTimer();
            statusIcon = FontIcon.of(Codicons.BELL_DOT, 14);
            setToolTipText(attentionTooltip);
        } else {
            stopBusyTimer();
            statusIcon = null;
            setToolTipText(null);
        }
        updatePaddingBorder();
        revalidate();
        repaint();
    }

    private void updatePaddingBorder() {
        int rightPadding = HORIZONTAL_PADDING;
        if (statusIcon != null) {
            rightPadding = HORIZONTAL_PADDING + statusIcon.getIconWidth() + STATUS_ICON_GAP;
        }
        setBorder(
                BorderFactory.createEmptyBorder(VERTICAL_PADDING, HORIZONTAL_PADDING, VERTICAL_PADDING, rightPadding));
    }

    @Override
    public void setSelected(boolean b) {
        super.setSelected(b);
        if (b) {
            if (FlatLaf.isLafDark()) {
                putClientProperty(FlatClientProperties.STYLE,
                        "background: $Component.accentColor; foreground: $Component.accentForeground");
            } else {
                putClientProperty(FlatClientProperties.STYLE, "background: #0d6efd; foreground: #ffffff");
            }
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
        Color amberAccent;
        Color amberTint;
        if (dark) {
            amberAccent = AMBER_DARK;
            amberTint = AMBER_TINT_DARK;
        } else {
            amberAccent = AMBER_LIGHT;
            amberTint = AMBER_TINT_LIGHT;
        }

        if (highlightWaiting && g instanceof Graphics2D) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(amberTint);
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
                if (getForeground() != null) {
                    statusIcon.setIconColor(getForeground());
                }
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
}
