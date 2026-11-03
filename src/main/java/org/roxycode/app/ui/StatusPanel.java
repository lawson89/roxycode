package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.TokenUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class StatusPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final transient EnvironmentService envService;

    final JLabel tokenCostLabel = new JLabel();
    final JLabel memLabel = new JLabel();
    private final Timer statsTimer;

    private transient ImplementationPlan.UsageSummary sessionUsage = ImplementationPlan.UsageSummary.EMPTY;

    public StatusPanel(EnvironmentService envService) {
        this.envService = envService;

        setLayout(new MigLayout("insets 2 15 2 15", "[] 20 [] push [] 16 []", "center"));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        JLabel osLabel = new JLabel(envService.getOsName(), envService.getOsIcon(), SwingConstants.LEFT);
        Font statusFont = osLabel.getFont().deriveFont(11f);
        osLabel.setFont(statusFont);
        osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());

        JLabel javaLabel = new JLabel("Java " + envService.getJavaVersion());
        javaLabel.setFont(statusFont);
        javaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        tokenCostLabel.setFont(statusFont);
        memLabel.setFont(statusFont);

        updateTokenDisplay();
        updateStats();

        add(osLabel);
        add(javaLabel);
        add(tokenCostLabel);
        add(memLabel);
        statsTimer = new Timer(5000, e -> updateStats());
        statsTimer.start();
    }

    @EventListener
    public void onTokenUsage(TokenUsageEvent event) {
        if (event == null) {
            return;
        }
        synchronized (this) {
            sessionUsage = sessionUsage.addEvent(event);
        }
        Edt.run(this::updateTokenDisplay);
    }

    synchronized void updateTokenDisplay() {
        tokenCostLabel.setText(String.format(Locale.US, "Session: %s tok (%s)",
                TokenUtils.formatTokenCount(sessionUsage.totalTokens()),
                TokenUtils.formatCostUsd(sessionUsage.estimatedCostUsd())));
        tokenCostLabel.setToolTipText(String.format(Locale.US,
                "Calls: %d | Total: %,d tok | Uncached In: %,d (%s) | Cached In: %,d (%d%%, %s) | Out+Think: %,d (%s) | Total Cost: %s",
                sessionUsage.modelCalls(), sessionUsage.totalTokens(), sessionUsage.uncachedInputTokens(),
                TokenUtils.formatCostUsd(sessionUsage.uncachedInputCostUsd()), sessionUsage.cachedInputTokens(),
                sessionUsage.cacheHitPercent(), TokenUtils.formatCostUsd(sessionUsage.cachedInputCostUsd()),
                sessionUsage.outputAndThinkingTokens(),
                TokenUtils.formatCostUsd(sessionUsage.outputAndThinkingCostUsd()),
                TokenUtils.formatCostUsd(sessionUsage.estimatedCostUsd())));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (statsTimer != null && !statsTimer.isRunning()) {
            statsTimer.start();
        }
    }

    @Override
    public void removeNotify() {
        if (statsTimer != null) {
            statsTimer.stop();
        }
        super.removeNotify();
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
        memLabel.setText("JVM Mem: " + usedMb + "/" + totalMb + "MB");
        int pct = 0;
        if (totalMb > 0) {
            pct = (int) Math.min(100L, Math.max(0L, (usedMb * 100L) / totalMb));
        }
        memLabel.setToolTipText(pct + "% JVM heap used (" + usedMb + " / " + totalMb + " MB)");
    }
}
