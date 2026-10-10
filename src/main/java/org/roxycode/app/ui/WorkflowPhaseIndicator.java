package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;

import javax.swing.*;
import java.awt.*;

/**
 * Shared UI component for workflow phase indicators with safe color fallbacks.
 */
public class WorkflowPhaseIndicator extends JPanel {
    private final WorkflowPhase phase;
    private final JLabel textLabel;
    private final JLabel iconLabel;
    private boolean isActive;
    private boolean isCompleted;

    public WorkflowPhaseIndicator(WorkflowPhase phase) {
        this(phase, "font: -1");
    }

    public WorkflowPhaseIndicator(WorkflowPhase phase, String fontStyle) {
        this.phase = phase;
        setLayout(new MigLayout("insets 2 8 2 8", "[]5[]", "center"));
        setOpaque(false);

        iconLabel = new JLabel();
        textLabel = new JLabel(phase.getDisplayName());
        if (fontStyle != null) {
            textLabel.putClientProperty(FlatClientProperties.STYLE, fontStyle);
        }

        add(iconLabel);
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
        Color accentColor = getSafeColor("Component.accentColor", Color.BLUE);
        Color accentForeground = getSafeColor("Component.accentForeground", Color.WHITE);
        Color foreground = getSafeColor("Label.foreground", Color.BLACK);
        Color disabledForeground = getSafeColor("Label.disabledForeground", Color.GRAY);

        if (isActive) {
            setOpaque(true);
            putClientProperty(FlatClientProperties.STYLE, "arc: 12");
            setBackground(accentColor);
            textLabel.setForeground(accentForeground);
            iconLabel.setIcon(FontIcon.of(phase.getIcon(), 14, accentForeground));
        } else {
            setOpaque(false);
            putClientProperty(FlatClientProperties.STYLE, "arc: 0");

            if (isCompleted) {
                textLabel.setForeground(foreground);
                iconLabel.setIcon(FontIcon.of(Codicons.CHECK, 14, foreground));
            } else {
                textLabel.setForeground(disabledForeground);
                iconLabel.setIcon(FontIcon.of(phase.getIcon(), 14, disabledForeground));
            }
        }
    }

    private Color getSafeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (textLabel != null) {
            updateStyle();
        }
    }
}
