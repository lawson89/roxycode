package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;

/**
 * Header panel containing application controls.
 */
public class HeaderPanel extends JPanel {
    public HeaderPanel() {
        setLayout(new MigLayout("insets 10 20 10 20", "push[]", "center"));
        
        // Use theme-aware styling
        putClientProperty(FlatClientProperties.STYLE, "background: $Panel.background");
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        add(new JLabel("🔔"));
    }
}