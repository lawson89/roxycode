package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import org.kordamp.ikonli.swing.FontIcon;
import javax.swing.*;
import java.awt.*;

/**
 * Styled navigation button for the sidebar.
 */
public class NavButton extends JButton {
    public NavButton(String text, Icon icon) {
        super(text, icon);
        setHorizontalAlignment(SwingConstants.LEFT);
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        putClientProperty("JButton.arc", 6);
        setIconTextGap(10);
    }

    @Override
    public void setSelected(boolean b) {
        super.setSelected(b);
        if (b) {
            if (FlatLaf.isLafDark()) {
                putClientProperty(FlatClientProperties.STYLE, 
                    "background: $Component.accentColor; " +
                    "foreground: $Component.accentForeground");
            } else {
                putClientProperty(FlatClientProperties.STYLE, 
                    "background: #0d6efd; " +
                    "foreground: #ffffff");
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
    }
}