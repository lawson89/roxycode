package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;

/** Shared UI component for workflow phase indicators with safe color fallbacks. */
public final class WorkflowPhaseIndicator extends JPanel {
    private static final long serialVersionUID = 1L;
    private final WorkflowPhase phase;
    private final JLabel textLabel;
    private boolean isActive;
    private boolean isCompleted;

    public WorkflowPhaseIndicator(WorkflowPhase phase) {
        this(phase, "font: -1");
    }

    public WorkflowPhaseIndicator(WorkflowPhase phase, String fontStyle) {
        this.phase = phase;
        setLayout(new MigLayout("insets 3 10 3 10", "[center]", "center"));
        setOpaque(false);

        textLabel = new JLabel(phase.getDisplayName());
        if (fontStyle != null) {
            textLabel.putClientProperty(FlatClientProperties.STYLE, fontStyle);
        }

        add(textLabel);
        updateStyle();
    }

    public WorkflowPhase getPhase() {
        return phase;
    }

    public void setActive(boolean active) {
        this.isActive = active;
        updateStyle();
    }

    public void setCompleted(boolean completed) {
        this.isCompleted = completed;
        updateStyle();
    }

    private void updateStyle() {
        Color accentColor = SwingHtmlUtils.getUiColor(Color.BLUE, "Component.accentColor");
        Color accentForeground =
                SwingHtmlUtils.getUiColor(Color.WHITE, "Component.accentForeground");
        Color foreground = SwingHtmlUtils.getUiColor(Color.BLACK, "Label.foreground");
        Color disabledForeground =
                SwingHtmlUtils.getUiColor(Color.GRAY, "Label.disabledForeground");

        if (isActive) {
            setOpaque(true);
            putClientProperty(FlatClientProperties.STYLE, "arc: 12");
            setBackground(accentColor);
            textLabel.setForeground(accentForeground);
        } else {
            setOpaque(false);
            putClientProperty(FlatClientProperties.STYLE, "arc: 0");

            if (isCompleted) {
                textLabel.setForeground(foreground);
            } else {
                textLabel.setForeground(disabledForeground);
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
