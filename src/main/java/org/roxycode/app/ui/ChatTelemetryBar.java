package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import org.roxycode.app.util.TokenUtils;

/**
 * Encapsulates context window usage, implicit prefix cache hit rate, and turn status indicators in {@link ChatPanel}.
 */
final class ChatTelemetryBar {

    private static final int CTX_USAGE_CRITICAL_PCT = 90;
    private static final int CTX_USAGE_WARNING_PCT = 75;
    private static final String HIDEMODE_3 = "hidemode 3";

    final JLabel ctxLabel;
    final JProgressBar ctxProgressBar;
    final JLabel cacheLabel;
    final JProgressBar cacheProgressBar;
    final JLabel turnLabel;

    ChatTelemetryBar(Font baseFont) {
        Font statusFont = baseFont.deriveFont(11f);
        ctxLabel = new JLabel();
        ctxLabel.setFont(statusFont);
        ctxLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        ctxProgressBar = new JProgressBar(0, 100);
        ctxProgressBar.setStringPainted(false);
        ctxProgressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6; foreground: $Component.accentColor");

        cacheLabel = new JLabel();
        cacheLabel.setFont(statusFont);
        cacheLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        cacheProgressBar = new JProgressBar(0, 100);
        cacheProgressBar.setStringPainted(false);
        cacheProgressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6; foreground: $Actions.Green");

        turnLabel = new JLabel();
        turnLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: $small.font; foreground: $Label.disabledForeground; background: darken($TextArea.background, 4%); arc: 8; border: 2,8,2,8");
    }

    void addToToolbar(JPanel toolbar) {
        toolbar.add(ctxLabel);
        toolbar.add(ctxProgressBar, "w 48!, h 6!");
        toolbar.add(cacheLabel, HIDEMODE_3);
        toolbar.add(cacheProgressBar, "w 44!, h 6!, " + HIDEMODE_3);
        toolbar.add(turnLabel, HIDEMODE_3);
    }

    void update(ChatModel model, String turnStatusText) {
        turnLabel.setText(turnStatusText);

        long lastPromptTokens = model.getLastPromptTokens();
        long lastCachedInputTokens = model.getLastCachedInputTokens();
        int contextWindow = Math.max(1, model.getLastContextWindowTokens());
        int ctxPct = (int) Math.min(100L, Math.max(0L, Math.round((lastPromptTokens * 100.0) / contextWindow)));
        int lastCacheHitPct = TokenUtils.computeCacheHitPercent(lastCachedInputTokens, lastPromptTokens);

        boolean hasTelemetryActivity = lastPromptTokens > 0 || model.getCurrentTurn() > 0;
        cacheLabel.setVisible(hasTelemetryActivity);
        cacheProgressBar.setVisible(hasTelemetryActivity);
        turnLabel.setVisible(hasTelemetryActivity);

        String formattedPrompt;
        if (lastPromptTokens > 0) {
            formattedPrompt = TokenUtils.formatTokenCount(lastPromptTokens);
        } else {
            formattedPrompt = "0";
        }
        String formattedWindow = TokenUtils.formatTokenCount(contextWindow);
        String formattedCached;
        if (lastCachedInputTokens > 0) {
            formattedCached = TokenUtils.formatTokenCount(lastCachedInputTokens);
        } else {
            formattedCached = "0";
        }

        ctxLabel.setText(String.format(Locale.US, "Ctx: %s / %s (%d%%)", formattedPrompt, formattedWindow, ctxPct));
        cacheLabel.setText(String.format(Locale.US, "Cache: %d%%", lastCacheHitPct));

        String ctxBarStyle;
        if (ctxPct >= CTX_USAGE_CRITICAL_PCT) {
            ctxBarStyle = "arc: 6; foreground: $Actions.Red";
        } else if (ctxPct >= CTX_USAGE_WARNING_PCT) {
            ctxBarStyle = "arc: 6; foreground: $Actions.Yellow";
        } else {
            ctxBarStyle = "arc: 6; foreground: $Component.accentColor";
        }
        ctxProgressBar.putClientProperty(FlatClientProperties.STYLE, ctxBarStyle);
        ctxProgressBar.setValue(ctxPct);
        String ctxTooltip = String.format(Locale.US, "Context window usage: %s / %s tokens (%d%%)", formattedPrompt,
                formattedWindow, ctxPct);
        ctxLabel.setToolTipText(ctxTooltip);
        ctxProgressBar.setToolTipText(ctxTooltip);

        cacheProgressBar.setValue(lastCacheHitPct);
        String cacheTooltip = String.format(Locale.US, "Implicit cache hit: %s / %s input tokens (%d%%)",
                formattedCached, formattedPrompt, lastCacheHitPct);
        cacheLabel.setToolTipText(cacheTooltip);
        cacheProgressBar.setToolTipText(cacheTooltip);
    }
}
