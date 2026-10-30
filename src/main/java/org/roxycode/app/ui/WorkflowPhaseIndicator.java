package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;

public final class WorkflowPhaseIndicator extends JPanel {
    private static final long serialVersionUID = 1L;
    private final WorkflowPhase phase;
    private final String defaultFontStyle;
    private final JLabel textLabel;
    private boolean active;
    private boolean completed;

    public WorkflowPhaseIndicator(WorkflowPhase phase) {
        this(phase, "font: -1");
    }

    public WorkflowPhaseIndicator(WorkflowPhase phase, String fontStyle) {
        this.phase = phase;
        if (fontStyle != null) {
            this.defaultFontStyle = fontStyle;
        } else {
            this.defaultFontStyle = "font: -1";
        }
        setLayout(new MigLayout("insets 0", "[center]", "center"));
        textLabel = new JLabel(phase.getDisplayName());
        add(textLabel);
        updateStyle();
    }

    public WorkflowPhase getPhase() {
        return phase;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isCompleted() {
        return completed;
    }

    JLabel getTextLabel() {
        return textLabel;
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

        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        textLabel.setIcon(null);
        setOpaque(active);
        if (active) {
            setBackground(accentColor);
            putClientProperty(FlatClientProperties.STYLE, "background: " + SwingHtmlUtils.toHex(accentColor));
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            textLabel.setForeground(Color.WHITE);
        } else {
            putClientProperty(FlatClientProperties.STYLE, null);
            if (completed) {
                textLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
                textLabel.setForeground(greenColor);
            } else {
                textLabel.putClientProperty(FlatClientProperties.STYLE, defaultFontStyle);
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
