package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.FunctionalSpec;
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
    private JLabel modeBadge;
    private JPanel progressTracker;
    private final WorkflowService workflowService;
    private final PlanManagerService planManagerService;
    private JLabel planLabel;

    public PhaseStepPanel(WorkflowService workflowService, PlanManagerService planManagerService) {
        this.planManagerService = planManagerService;
        this.workflowService = workflowService;
        setLayout(new MigLayout("insets 0, gapx 10", "[]", "center"));
        setOpaque(false);
        initComponents();
        workflowService.addPhaseListener(this::updateActivePhase);
        planManagerService.addListener((fs, ts) -> updatePlanDisplay(workflowService.getCurrentPhase()));
        
        // Initialize with current phase
        updateActivePhase(workflowService.getCurrentPhase());
    }

    private void initComponents() {
        planLabel = new JLabel();
        planLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        add(planLabel, "hidemode 3");
        // Mode Badge for EXPLORE
        modeBadge = new JLabel("Explore");
        modeBadge.setIcon(FontIcon.of(WorkflowPhase.EXPLORE.getIcon(), 16, UIManager.getColor("List.selectionForeground")));
        modeBadge.putClientProperty(FlatClientProperties.STYLE, "arc: 12; font: +1; background: $Component.accentColor; foreground: $List.selectionForeground");
        modeBadge.setOpaque(true);
        modeBadge.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        add(modeBadge, "hidemode 3");

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 10", "[]", "center"));
        progressTracker.setOpaque(false);

        WorkflowPhase[] phases = WorkflowPhase.values();
        List<WorkflowPhase> linearPhases = new ArrayList<>();
        for (WorkflowPhase p : phases) {
            if (p != WorkflowPhase.EXPLORE) {
                linearPhases.add(p);
            }
        }

        for (int i = 0; i < linearPhases.size(); i++) {
            WorkflowPhase phase = linearPhases.get(i);
            PhaseIndicator indicator = new PhaseIndicator(phase);
            indicators.add(indicator);
            progressTracker.add(indicator);
            
            if (i < linearPhases.size() - 1) {
                JLabel separator = new JLabel(FontIcon.of(Codicons.CHEVRON_RIGHT, 12, UIManager.getColor("Label.disabledForeground")));
                progressTracker.add(separator);
            }
        }
        add(progressTracker, "hidemode 3");
    }

    private void updatePlanDisplay(WorkflowPhase currentPhase) {
        if (planLabel == null) return;
        FunctionalSpec spec = planManagerService.getCurrentFunctionalSpec();
        if (currentPhase == WorkflowPhase.EXPLORE || spec == null || spec.title() == null || spec.title().isBlank()) {
            planLabel.setVisible(false);
        } else {
            planLabel.setText(spec.title().toUpperCase() + ":");
            planLabel.setVisible(true);
        }
    }

    private void updateActivePhase(WorkflowPhase currentPhase) {
        boolean isExplore = (currentPhase == WorkflowPhase.EXPLORE);
        if (modeBadge != null) modeBadge.setVisible(isExplore);
        if (progressTracker != null) progressTracker.setVisible(!isExplore);
        updatePlanDisplay(currentPhase);

        java.util.Set<WorkflowPhase> visited = workflowService.getVisitedPhases();
        for (PhaseIndicator indicator : indicators) {
            indicator.setActive(indicator.phase == currentPhase);
            indicator.setCompleted(visited.contains(indicator.phase));
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
