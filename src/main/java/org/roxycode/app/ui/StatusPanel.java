package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.TokenUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class StatusPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String BAR_ARC_STYLE = "arc: 6";
    private static final String BAR_SIZE_CONSTRAINTS = "w 60!, h 8!";

    private final transient EnvironmentService envService;
    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;

    final JLabel ctxLabel = new JLabel();
    final JProgressBar ctxProgressBar = new JProgressBar(0, 100);
    final JLabel tokenCostLabel = new JLabel();
    final JLabel memLabel = new JLabel();
    final JProgressBar memProgressBar = new JProgressBar(0, 100);
    private final Timer statsTimer;

    private transient ImplementationPlan.UsageSummary sessionUsage = ImplementationPlan.UsageSummary.EMPTY;
    private int lastPromptTokens = 0;
    private int lastCachedInputTokens = 0;
    private int lastContextWindowTokens = GeminiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS;

    public StatusPanel(EnvironmentService envService) {
        this(envService, null, null);
    }

    @Autowired
    public StatusPanel(EnvironmentService envService, @Nullable SettingsService settingsService,
            @Nullable ProjectService projectService) {
        this.envService = envService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.lastContextWindowTokens = resolveConfiguredContextWindowTokens();

        setLayout(new MigLayout("insets 2 15 2 15", "[] 20 [] push [] 6 [] 16 [] 16 [] 6 []", "center"));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        JLabel osLabel = new JLabel(envService.getOsName(), envService.getOsIcon(), SwingConstants.LEFT);
        Font statusFont = osLabel.getFont().deriveFont(11f);
        osLabel.setFont(statusFont);
        osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());

        JLabel javaLabel = new JLabel("Java " + envService.getJavaVersion());
        javaLabel.setFont(statusFont);
        javaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        ctxLabel.setFont(statusFont);
        ctxProgressBar.setStringPainted(false);
        ctxProgressBar.putClientProperty(FlatClientProperties.STYLE, BAR_ARC_STYLE);

        tokenCostLabel.setFont(statusFont);

        memLabel.setFont(statusFont);
        memProgressBar.setStringPainted(false);
        memProgressBar.putClientProperty(FlatClientProperties.STYLE, BAR_ARC_STYLE);

        updateTokenDisplay();
        updateStats();

        add(osLabel);
        add(javaLabel);
        add(ctxLabel);
        add(ctxProgressBar, BAR_SIZE_CONSTRAINTS);
        add(tokenCostLabel);
        add(memLabel);
        add(memProgressBar, BAR_SIZE_CONSTRAINTS);

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
            lastPromptTokens = Math.max(0, event.promptTokens());
            lastCachedInputTokens = Math.max(0, event.cachedInputTokens());
            if (event.contextWindowTokens() > 0) {
                lastContextWindowTokens = event.contextWindowTokens();
            } else {
                lastContextWindowTokens = resolveConfiguredContextWindowTokens();
            }
        }
        Edt.run(this::updateTokenDisplay);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        synchronized (this) {
            lastPromptTokens = 0;
            lastCachedInputTokens = 0;
            lastContextWindowTokens = resolveConfiguredContextWindowTokens();
        }
        Edt.run(this::updateTokenDisplay);
    }

    private int resolveConfiguredContextWindowTokens() {
        String modelName = "";
        if (projectService != null) {
            modelName = projectService.getEffectiveGeminiModel();
        } else if (settingsService != null) {
            modelName = SettingsService.resolveGlobalGeminiModel(settingsService);
        }
        if (settingsService != null) {
            GeminiModelConfig cfg = settingsService.resolveModelConfig(modelName);
            if (cfg != null && cfg.contextWindowTokens() > 0) {
                return cfg.contextWindowTokens();
            }
        }
        if (StringUtils.isNotBlank(modelName)) {
            return GeminiModelConfig.defaultForApiName(modelName).contextWindowTokens();
        }
        return GeminiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS;
    }

    synchronized void updateTokenDisplay() {
        int contextWindow = Math.max(1, lastContextWindowTokens);
        int ctxPct = (int) Math.min(100L, Math.max(0L, Math.round((lastPromptTokens * 100.0) / contextWindow)));
        int lastCacheHitPct = TokenUtils.computeCacheHitPercent(lastCachedInputTokens, lastPromptTokens);

        if (lastPromptTokens > 0) {
            ctxLabel.setText(String.format(Locale.US, "Ctx: %s / %s (%d%%) | Cache: %d%%",
                    TokenUtils.formatTokenCount(lastPromptTokens), TokenUtils.formatTokenCount(contextWindow), ctxPct,
                    lastCacheHitPct));
        } else {
            ctxLabel.setText(String.format(Locale.US, "Ctx: 0 / %s (0%%)", TokenUtils.formatTokenCount(contextWindow)));
        }
        ctxProgressBar.setValue(ctxPct);
        String ctxTooltip = String.format(Locale.US,
                "Context window: %,d / %,d tokens (%d%%) | Cached input: %,d tokens (%d%%)", lastPromptTokens,
                contextWindow, ctxPct, lastCachedInputTokens, lastCacheHitPct);
        ctxLabel.setToolTipText(ctxTooltip);
        ctxProgressBar.setToolTipText(ctxTooltip);

        tokenCostLabel.setText(String.format(Locale.US, "Session: %s tok (%s)",
                TokenUtils.formatTokenCount(sessionUsage.totalTokens()),
                TokenUtils.formatCostUsd(sessionUsage.estimatedCostUsd())));
        tokenCostLabel.setToolTipText(String.format(Locale.US,
                "Calls: %d | Total: %,d tok | Uncached In: %,d | Cached In: %,d (%d%%) | Out+Think: %,d | Cost: %s",
                sessionUsage.modelCalls(), sessionUsage.totalTokens(), sessionUsage.uncachedInputTokens(),
                sessionUsage.cachedInputTokens(), sessionUsage.cacheHitPercent(),
                sessionUsage.outputAndThinkingTokens(), TokenUtils.formatCostUsd(sessionUsage.estimatedCostUsd())));
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
        memLabel.setText("Mem: " + usedMb + "/" + totalMb + "MB");
        int pct = 0;
        if (totalMb > 0) {
            pct = (int) Math.min(100L, Math.max(0L, (usedMb * 100L) / totalMb));
        }
        memProgressBar.setValue(pct);
        memProgressBar.setToolTipText(pct + "% JVM heap used (" + usedMb + " / " + totalMb + " MB)");
    }
}
