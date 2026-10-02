package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.kordamp.ikonli.swing.FontIcon;
import org.kordamp.ikonli.codicons.Codicons;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A UI component that visualizes the workflow phases and tracks current progress.
 */
public class PhaseStepPanel extends JPanel {

    private final List<PhaseIndicator> indicators = new ArrayList<>();
    private final WorkflowService workflowService;

    public PhaseStepPanel(WorkflowService workflowService) {
        this.workflowService = workflowService;
        setLayout(new MigLayout("insets 0, gapx 10", "[]", "center"));
        setOpaque(false);
        initComponents();
        workflowService.addPhaseListener(this::updateActivePhase);
        
        // Initialize with current phase
        updateActivePhase(workflowService.getCurrentPhase());
    }

    private void initComponents() {
        WorkflowPhase[] phases = WorkflowPhase.values();
        for (int i = 0; i < phases.length; i++) {
            WorkflowPhase phase = phases[i];
            PhaseIndicator indicator = new PhaseIndicator(phase);
            indicators.add(indicator);
            add(indicator);
            
            if (i < phases.length - 1) {
                JLabel separator = new JLabel(FontIcon.of(Codicons.CHEVRON_RIGHT, 12, UIManager.getColor("Label.disabledForeground")));
                add(separator);
            }
        }
    }

    private void updateActivePhase(WorkflowPhase currentPhase) {
        for (PhaseIndicator indicator : indicators) {
            indicator.setActive(indicator.phase == currentPhase);
            indicator.setCompleted(indicator.phase.ordinal() < currentPhase.ordinal());
        }
        revalidate();
        repaint();
    }

    private static class PhaseIndicator extends JPanel {
        private final WorkflowPhase phase;
        private final JLabel iconLabel;
        private final JLabel textLabel;
        private boolean isActive;
        private boolean isCompleted;

        public PhaseIndicator(WorkflowPhase phase) {
            this.phase = phase;
            setLayout(new MigLayout("insets 2 8 2 8, gapx 5", "[][]", "center"));
            setOpaque(false);
            
            // Apply a slight round border
            putClientProperty(FlatClientProperties.STYLE, "arc: 12");

            iconLabel = new JLabel(FontIcon.of(phase.getIcon(), 16, UIManager.getColor("Label.disabledForeground")));
            textLabel = new JLabel(phase.getDisplayName());
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

            add(iconLabel);
            add(textLabel);
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
            if (isActive) {
                setOpaque(true);
                // Use a slightly lighter version of the accent color for background if possible, 
                // or just the accent color if it's high contrast.
                setBackground(UIManager.getColor("Component.accentColor"));
                if (getBackground() == null) {
                    setBackground(UIManager.getColor("List.selectionBackground"));
                }
                textLabel.setForeground(UIManager.getColor("List.selectionForeground"));
                iconLabel.setIcon(FontIcon.of(phase.getIcon(), 16, UIManager.getColor("List.selectionForeground")));
            } else if (isCompleted) {
                setOpaque(false);
                Color successColor = new Color(100, 180, 100);
                textLabel.setForeground(successColor);
                iconLabel.setIcon(FontIcon.of(phase.getIcon(), 16, successColor));
            } else {
                setOpaque(false);
                textLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
                iconLabel.setIcon(FontIcon.of(phase.getIcon(), 16, UIManager.getColor("Label.disabledForeground")));
            }
        }
    }
}
