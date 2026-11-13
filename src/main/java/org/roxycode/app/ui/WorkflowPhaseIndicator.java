package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;

final class WorkflowPhaseIndicator extends JPanel {
    private static final long serialVersionUID = 1L;
    private final WorkflowPhase phase;
    private final JLabel textLabel;
    private boolean active;
    private boolean completed;

    public WorkflowPhaseIndicator(WorkflowPhase phase) {
        this.phase = phase;
        setLayout(new MigLayout("insets 0", "[center]", "center"));
        textLabel = new JLabel(formatPhaseLabel(phase));
        textLabel.setIconTextGap(4);
        String tooltip = phase.getTooltipHtml();
        setToolTipText(tooltip);
        textLabel.setToolTipText(tooltip);
        add(textLabel);
        updateStyle();
    }

    static String formatPhaseLabel(WorkflowPhase phase) {
        return switch (phase) {
            case PLAN -> "1. Plan";
            case CODE -> "2. Code";
            case REVIEW -> "3. Review";
            default -> phase.getDisplayName();
        };
    }

    static Codicons resolvePhaseIcon(WorkflowPhase phase) {
        return switch (phase) {
            case PLAN -> Codicons.LIST_ORDERED;
            case CODE -> Codicons.CODE;
            case REVIEW -> Codicons.CHECK_ALL;
            default -> Codicons.CIRCLE_OUTLINE;
        };
    }

    public WorkflowPhase getPhase() {
        return phase;
    }

    public void setActive(boolean active) {
        this.active = active;
        updateStyle();
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
        updateStyle();
    }

    private void updateStyle() {
        Color accentColor = SwingHtmlUtils.getAccentColor();
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green");
        Color disabledFg = SwingHtmlUtils.getMutedForegroundColor();

        setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        setOpaque(false);
        if (active) {
            setBackground(accentColor);
            putClientProperty(FlatClientProperties.STYLE, "arc: 8; background: " + SwingHtmlUtils.toHex(accentColor));
            textLabel.setIcon(FontIcon.of(resolvePhaseIcon(phase), 12, Color.WHITE));
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            textLabel.setForeground(Color.WHITE);
        } else {
            putClientProperty(FlatClientProperties.STYLE, null);
            if (completed) {
                textLabel.setIcon(FontIcon.of(Codicons.CHECK, 12, greenColor));
                textLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
                textLabel.setForeground(greenColor);
            } else {
                textLabel.setIcon(FontIcon.of(resolvePhaseIcon(phase), 12, disabledFg));
                textLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
                textLabel.setForeground(disabledFg);
            }
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (textLabel != null) {
            updateStyle();
        }
    }
}
