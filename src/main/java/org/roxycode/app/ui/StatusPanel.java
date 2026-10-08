package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.EnvironmentService;
import javax.swing.*;
import java.awt.*;

/**
 * Bottom status bar with system info.
 */
public class StatusPanel extends JPanel {
    private final EnvironmentService envService;
    private final JLabel memLabel;

    public StatusPanel(EnvironmentService envService) {
        this.envService = envService;
        setLayout(new MigLayout("insets 2 15 2 15", "[] 20 [] push []", "center"));
        
        // Use theme-aware styling
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")));

        JLabel osLabel = new JLabel(envService.getOsName());
        osLabel.setIcon(envService.getOsIcon());
        osLabel.setFont(osLabel.getFont().deriveFont(11f));
        osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());
        
        JLabel javaLabel = new JLabel("Java " + envService.getJavaVersion());
        javaLabel.setFont(osLabel.getFont());
        javaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        
        memLabel = new JLabel();
        memLabel.setFont(osLabel.getFont());
        updateStats();

        add(osLabel);
        add(javaLabel);
        add(memLabel);

        // Update memory usage every 5 seconds
        Timer timer = new Timer(5000, e -> updateStats());
        timer.start();
    }

    private void updateStats() {
        memLabel.setText("Mem: " + envService.getMemoryUsageMb() + "/" + envService.getTotalMemoryMb() + "MB");
    }
}
