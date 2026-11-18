package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.ImplementationPlan;
import org.springframework.lang.Nullable;

/**
 * Animated activity indicator strip for {@link ChatPanel}, showing live status text, plan step progress, and elapsed
 * turn time.
 */
final class ChatActivityStrip extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int ACTIVITY_ANIMATION_DELAY_MS = 500;
    private static final int ACTIVITY_ELAPSED_DELAY_MS = 1000;
    private static final Codicons[] HOURGLASS_FRAMES = {Codicons.CIRCLE_FILLED, Codicons.CIRCLE_OUTLINE};

    private final transient ChatModel model;
    private final transient @Nullable PlanService planService;
    private final transient BooleanSupplier loadingSupplier;

    final JLabel activityStatusLabel;
    final JLabel activityStepLabel;
    final JLabel activityElapsedLabel;
    transient FontIcon activityIcon;
    final Timer activityAnimationTimer;
    final Timer activityElapsedTimer;
    private int activityIconFrameIndex;
    private long turnStartMillis;
    private Boolean lastActivityWarningAccent;

    ChatActivityStrip(ChatModel model, @Nullable PlanService planService, BooleanSupplier loadingSupplier) {
        super(new MigLayout("insets 0, fillx, hidemode 3", "[grow, fill]12[]6[]", "center"));
        this.model = model;
        this.planService = planService;
        this.loadingSupplier = loadingSupplier;
        setVisible(false);

        activityIcon = FontIcon.of(HOURGLASS_FRAMES[0], 13, SwingHtmlUtils.getAccentColor());
        activityStatusLabel = new JLabel("");
        activityStatusLabel.setIcon(activityIcon);
        activityStatusLabel.setIconTextGap(8);
        activityStatusLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        activityStepLabel = new JLabel("");
        activityStepLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: -1 bold; foreground: $Component.accentColor");
        activityStepLabel.setVisible(false);

        activityElapsedLabel = new JLabel("0:00");
        Font baseElapsedFont = activityElapsedLabel.getFont();
        activityElapsedLabel
                .setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(11, baseElapsedFont.getSize() - 1)));
        activityElapsedLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        add(activityStatusLabel, "growx, wmin 0");
        add(activityStepLabel, "hidemode 3");
        add(activityElapsedLabel, "alignx right");

        int stripHeight = Math.max(26, activityStatusLabel.getPreferredSize().height + 12);
        setMinimumSize(new Dimension(0, stripHeight));
        setPreferredSize(new Dimension(0, stripHeight));

        activityAnimationTimer = new Timer(ACTIVITY_ANIMATION_DELAY_MS, e -> advanceActivityIconFrame());
        activityElapsedTimer = new Timer(ACTIVITY_ELAPSED_DELAY_MS, e -> updateElapsedTimeLabel());
        applyStyling();
    }

    static boolean isRateLimitOrRetryStatus(String statusText) {
        if (statusText == null || statusText.isEmpty()) {
            return false;
        }
        String lower = statusText.toLowerCase(Locale.ROOT);
        return lower.contains("rate limit") || lower.contains("retrying");
    }

    static String formatElapsedSeconds(long elapsedSeconds) {
        long safeSeconds = Math.max(0L, elapsedSeconds);
        long minutes = safeSeconds / 60L;
        long seconds = safeSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    void applyStyling() {
        String currentText = "";
        if (activityStatusLabel != null) {
            currentText = activityStatusLabel.getText();
        }
        Color accent;
        if (isRateLimitOrRetryStatus(currentText)) {
            accent = SwingHtmlUtils.getWarningColor();
        } else {
            accent = SwingHtmlUtils.getAccentColor();
        }
        SwingHtmlUtils.applyTintedBannerStyle(this, accent, 5, 12, 12);
        if (activityIcon != null) {
            activityIcon.setIconColor(accent);
        }
        lastActivityWarningAccent = isRateLimitOrRetryStatus(currentText);
    }

    void advanceActivityIconFrame() {
        if (!loadingSupplier.getAsBoolean() || activityIcon == null) {
            stopTimers();
            return;
        }
        activityIconFrameIndex = (activityIconFrameIndex + 1) % HOURGLASS_FRAMES.length;
        activityIcon.setIkon(HOURGLASS_FRAMES[activityIconFrameIndex]);
        repaint();
    }

    void updateElapsedTimeLabel() {
        if (!loadingSupplier.getAsBoolean()) {
            return;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - turnStartMillis) / 1000L);
        activityElapsedLabel.setText(formatElapsedSeconds(elapsedSeconds));
    }

    void startTimers() {
        turnStartMillis = System.currentTimeMillis();
        activityIconFrameIndex = 0;
        if (activityIcon != null) {
            activityIcon.setIkon(HOURGLASS_FRAMES[0]);
        }
        activityElapsedLabel.setText("0:00");
        updateStepLabel();
        applyStyling();
        setVisible(true);
        if (activityAnimationTimer != null && !activityAnimationTimer.isRunning()) {
            activityAnimationTimer.start();
        }
        if (activityElapsedTimer != null && !activityElapsedTimer.isRunning()) {
            activityElapsedTimer.start();
        }
    }

    void stopTimers() {
        if (activityAnimationTimer != null && activityAnimationTimer.isRunning()) {
            activityAnimationTimer.stop();
        }
        if (activityElapsedTimer != null && activityElapsedTimer.isRunning()) {
            activityElapsedTimer.stop();
        }
    }

    void reset() {
        stopTimers();
        setVisible(false);
        activityStatusLabel.setText("");
        activityStatusLabel.setToolTipText(null);
        activityStepLabel.setText("");
        activityStepLabel.setVisible(false);
        activityElapsedLabel.setText("0:00");
    }

    void updateStepLabel() {
        if (activityStepLabel == null) {
            return;
        }
        if (!model.isTaskMode() || planService == null || planService.getCurrentPlan() == null) {
            activityStepLabel.setText("");
            activityStepLabel.setVisible(false);
            return;
        }
        ImplementationPlan plan = planService.getCurrentPlan();
        List<ImplementationPlan.TechStep> steps = plan.technicalSteps();
        if (steps == null || steps.isEmpty()) {
            activityStepLabel.setText("");
            activityStepLabel.setVisible(false);
            return;
        }
        activityStepLabel.setText("Step " + plan.completedStepCount() + "/" + steps.size() + " ·");
        activityStepLabel.setVisible(true);
    }

    void updateStatus(String statusText) {
        String text = Objects.requireNonNullElse(statusText, "").trim();
        boolean stripWasVisible = isVisible();
        boolean stepWasVisible = activityStepLabel.isVisible();
        activityStatusLabel.setText(text);
        if (text.isEmpty()) {
            activityStatusLabel.setToolTipText(null);
        } else {
            activityStatusLabel.setToolTipText(text);
        }
        updateStepLabel();
        boolean warningAccent = isRateLimitOrRetryStatus(text);
        if (lastActivityWarningAccent == null || lastActivityWarningAccent.booleanValue() != warningAccent) {
            applyStyling();
        }
        if (loadingSupplier.getAsBoolean() && !text.isEmpty() && !isVisible()) {
            setVisible(true);
        }
        if (stripWasVisible != isVisible() || stepWasVisible != activityStepLabel.isVisible()) {
            revalidate();
        }
        repaint();
    }

    String getStatusText() {
        return activityStatusLabel.getText();
    }
}
