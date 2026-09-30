package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;

/**
 * Bottom status bar with system info.
 */
public class StatusPanel extends JPanel {
    public StatusPanel() {
        setLayout(new MigLayout("insets 2 15 2 15", "[grow]push[]", "center"));
        
        // Use theme-aware styling
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")));

        JLabel infoLabel = new JLabel("System Ready");
        infoLabel.setFont(infoLabel.getFont().deriveFont(11f));
        
        long memory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        JLabel statsLabel = new JLabel("Java " + System.getProperty("java.version") + " | Mem: " + memory + "MB");
        statsLabel.setFont(statsLabel.getFont().deriveFont(11f));

        add(infoLabel);
        add(statsLabel);
    }
}