package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;

/** Shared UI component for workflow phase indicators with safe color fallbacks. */
public final class WorkflowPhaseIndicator extends JPanel {
    private static final long serialVersionUID = 1L;
    private final WorkflowPhase phase;
    private final String defaultFontStyle;
    private final JLabel textLabel;
    private boolean isActive;
    private boolean isCompleted;

    public WorkflowPhaseIndicator(WorkflowPhase phase) {
        this(phase, "font: -1");
    }

    public WorkflowPhaseIndicator(WorkflowPhase phase, String fontStyle) {
        this.phase = phase;
        this.defaultFontStyle = fontStyle != null ? fontStyle : "font: -1";
        setLayout(new MigLayout("insets 0", "[center]", "center"));

        textLabel = new JLabel(phase.getDisplayName());
        textLabel.setIconTextGap(6);

        add(textLabel);
        updateStyle();
    }

    public WorkflowPhase getPhase() {
        return phase;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    JLabel getTextLabel() {
        return textLabel;
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
        Color accentColor =
                SwingHtmlUtils.getUiColor(
                        new Color(0x00, 0x7a, 0xcc), "Component.accentColor", "Actions.Blue");
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green");
        Color disabledForeground =
                SwingHtmlUtils.getUiColor(
                        Color.GRAY, "Label.disabledForeground", "Component.infoForeground");

        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        if (isActive) {
            String accentHex = SwingHtmlUtils.toHex(accentColor);
            setOpaque(true);
            putClientProperty(
                    FlatClientProperties.STYLE,
                    "arc: 10; background: fade(" + accentHex + ", 18%)");
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            textLabel.setForeground(accentColor);
            textLabel.setIcon(FontIcon.of(phase.getIcon(), 14, accentColor));
        } else if (isCompleted) {
            String greenHex = SwingHtmlUtils.toHex(greenColor);
            setOpaque(true);
            putClientProperty(
                    FlatClientProperties.STYLE, "arc: 10; background: fade(" + greenHex + ", 12%)");
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            textLabel.setForeground(greenColor);
            textLabel.setIcon(FontIcon.of(Codicons.CHECK, 14, greenColor));
        } else {
            setOpaque(false);
            putClientProperty(FlatClientProperties.STYLE, null);
            textLabel.putClientProperty(FlatClientProperties.STYLE, defaultFontStyle);
            textLabel.setForeground(disabledForeground);
            textLabel.setIcon(FontIcon.of(phase.getIcon(), 14, disabledForeground));
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
