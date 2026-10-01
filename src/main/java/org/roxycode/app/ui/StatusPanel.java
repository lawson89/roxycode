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
    private final JLabel statsLabel;

    public StatusPanel(EnvironmentService envService) {
        this.envService = envService;
        setLayout(new MigLayout("insets 2 15 2 15", "[grow]push[]", "center"));
        
        // Use theme-aware styling
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")));

        JLabel osLabel = new JLabel(envService.getOsName());
        osLabel.setIcon(envService.getOsIcon());
        osLabel.setFont(osLabel.getFont().deriveFont(11f));
        osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());
        
        statsLabel = new JLabel();
        statsLabel.setFont(statsLabel.getFont().deriveFont(11f));
        updateStats();

        add(osLabel);
        add(statsLabel);

        // Update memory usage every 5 seconds
        Timer timer = new Timer(5000, e -> updateStats());
        timer.start();
    }

    private void updateStats() {
        statsLabel.setText("Java " + envService.getJavaVersion() + " | Mem: " + envService.getMemoryUsageMb() + "/" + envService.getTotalMemoryMb() + "MB");
    }
}