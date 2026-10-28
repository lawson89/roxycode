package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class StatusPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final transient EnvironmentService envService;
    private final JLabel memLabel = new JLabel();
    private final Timer statsTimer;

    public StatusPanel(EnvironmentService envService) {
        this.envService = envService;
        setLayout(new MigLayout("insets 2 15 2 15", "[] 20 [] push []", "center"));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        JLabel osLabel = new JLabel(envService.getOsName(), envService.getOsIcon(), SwingConstants.LEFT);
        osLabel.setFont(osLabel.getFont().deriveFont(11f));
        osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());

        JLabel javaLabel = new JLabel("Java " + envService.getJavaVersion());
        javaLabel.setFont(osLabel.getFont());
        javaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        memLabel.setFont(osLabel.getFont());
        updateStats();

        add(osLabel);
        add(javaLabel);
        add(memLabel);

        statsTimer = new Timer(5000, e -> updateStats());
        statsTimer.start();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (statsTimer != null && !statsTimer.isRunning())
            statsTimer.start();
    }

    @Override
    public void removeNotify() {
        if (statsTimer != null)
            statsTimer.stop();
        super.removeNotify();
    }

    Timer getStatsTimer() {
        return statsTimer;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        updateBorder();
    }

    private void updateBorder() {
        setBorder(SwingHtmlUtils.createThemeMatteBorder(1, 0, 0, 0));
    }

    void updateStats() {
        memLabel.setText("Mem: " + envService.getMemoryUsageMb() + "/" + envService.getTotalMemoryMb() + "MB");
    }
}
