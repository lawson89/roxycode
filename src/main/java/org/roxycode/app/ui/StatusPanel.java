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
    private final JProgressBar memProgressBar = new JProgressBar(0, 100);
    private final Timer statsTimer;

    public StatusPanel(EnvironmentService envService) {
        this.envService = envService;
        setLayout(new MigLayout("insets 2 15 2 15", "[] 20 [] push [] 6 []", "center"));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        JLabel osLabel = new JLabel(envService.getOsName(), envService.getOsIcon(), SwingConstants.LEFT);
        osLabel.setFont(osLabel.getFont().deriveFont(11f));
        osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());

        JLabel javaLabel = new JLabel("Java " + envService.getJavaVersion());
        javaLabel.setFont(osLabel.getFont());
        javaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        memLabel.setFont(osLabel.getFont());

        memProgressBar.setStringPainted(false);
        memProgressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        updateStats();

        add(osLabel);
        add(javaLabel);
        add(memLabel);
        add(memProgressBar, "w 60!, h 8!");

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

    JProgressBar getMemProgressBar() {
        return memProgressBar;
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
        long usedMb = envService.getMemoryUsageMb();
        long totalMb = envService.getTotalMemoryMb();
        memLabel.setText("Mem: " + usedMb + "/" + totalMb + "MB");
        int pct = totalMb > 0 ? (int) Math.min(100L, Math.max(0L, (usedMb * 100L) / totalMb)) : 0;
        memProgressBar.setValue(pct);
        memProgressBar.setToolTipText(pct + "% JVM heap used (" + usedMb + " / " + totalMb + " MB)");
    }
}
