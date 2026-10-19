package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Styled navigation button for the sidebar. */
public final class NavButton extends JButton {
    private static final long serialVersionUID = 1L;
    private boolean busy;
    private boolean waitingApproval;
    private transient FontIcon statusIcon;

    public NavButton(String text, Icon icon) {
        super(text, icon);
        setHorizontalAlignment(SwingConstants.LEFT);
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        putClientProperty(
                FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        putClientProperty("JButton.arc", 6);
        setIconTextGap(10);
    }

    public void setBusy(boolean busy) {
        if (this.busy == busy) {
            return;
        }
        this.busy = busy;
        updateStatusIcon();
        repaint();
    }

    public boolean isBusy() {
        return busy;
    }

    public void setWaitingApproval(boolean waitingApproval) {
        if (this.waitingApproval == waitingApproval) {
            return;
        }
        this.waitingApproval = waitingApproval;
        updateStatusIcon();
        repaint();
    }

    public boolean isWaitingApproval() {
        return waitingApproval;
    }

    FontIcon getStatusIcon() {
        return statusIcon;
    }

    @SuppressWarnings({
        "PMD.NullAssignment",
    })
    private void updateStatusIcon() {
        if (busy) {
            statusIcon = FontIcon.of(FontAwesomeSolid.HOURGLASS_HALF, 13);
        } else if (waitingApproval) {
            statusIcon = FontIcon.of(Codicons.QUESTION, 14);
        } else {
            statusIcon = null;
        }
    }

    @Override
    public void setSelected(boolean b) {
        super.setSelected(b);
        if (b) {
            if (FlatLaf.isLafDark()) {
                putClientProperty(
                        FlatClientProperties.STYLE,
                        "background: $Component.accentColor; "
                                + "foreground: $Component.accentForeground");
            } else {
                putClientProperty(
                        FlatClientProperties.STYLE,
                        "background: #0d6efd; " + "foreground: #ffffff");
            }
            setContentAreaFilled(true);
        } else {
            putClientProperty(FlatClientProperties.STYLE, "");
            setContentAreaFilled(false);
        }
        updateIconColor();
    }

    private void updateIconColor() {
        Icon icon = getIcon();
        if (icon instanceof FontIcon) {
            FontIcon fontIcon = (FontIcon) icon;
            Color fg = getForeground();
            if (fg != null) {
                fontIcon.setIconColor(fg);
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
        super.paintComponent(g);
        if (statusIcon != null) {
            Color color =
                    isSelected() ? getForeground() : UIManager.getColor("Component.accentColor");
            if (color == null) {
                color = getForeground();
            }
            if (color != null) {
                statusIcon.setIconColor(color);
            }
            int x = getWidth() - statusIcon.getIconWidth() - 12;
            int y = (getHeight() - statusIcon.getIconHeight()) / 2;
            statusIcon.paintIcon(this, g, x, y);
        }
    }
}
