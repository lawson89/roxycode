package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.ImplementationPlan;
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
        planManagerService.addListener((plan) -> updatePlanDisplay(workflowService.getCurrentPhase()));
        
        // Initialize with current phase
        updateActivePhase(workflowService.getCurrentPhase());
    }

    private void initComponents() {
        planLabel = new JLabel();
        planLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        add(planLabel, "hidemode 3");
                // Mode Badge for EXPLORE
        modeBadge = new JLabel("Explore");
        modeBadge.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        modeBadge.setOpaque(false);
        add(modeBadge, "hidemode 3");

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 10", "[]", "center"));
        progressTracker.setOpaque(false);

                WorkflowPhase[] phases = WorkflowPhase.values();
        for (WorkflowPhase phase : phases) {
            indicators.add(new PhaseIndicator(phase));
        }
    }

    private void updatePlanDisplay(WorkflowPhase currentPhase) {
        if (planLabel == null) return;
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (currentPhase == WorkflowPhase.EXPLORE || plan == null || plan.title() == null || plan.title().isBlank()) {
            planLabel.setVisible(false);
        } else {
            planLabel.setText(plan.title().toUpperCase());
            planLabel.setVisible(true);
        }
    }

        private void updateActivePhase(WorkflowPhase currentPhase) {
        if (modeBadge != null) modeBadge.setVisible(false);
        updatePlanDisplay(currentPhase);

        progressTracker.removeAll();
        for (PhaseIndicator indicator : indicators) {
            if (indicator.phase == currentPhase) {
                indicator.setActive(true);
                progressTracker.add(indicator);
            } else {
                indicator.setActive(false);
            }
        }
        revalidate();
        repaint();
    }

        private static class PhaseIndicator extends JPanel {
        private final WorkflowPhase phase;
        private final JLabel textLabel;
        private boolean isActive;
        private boolean isCompleted;

        public PhaseIndicator(WorkflowPhase phase) {
            this.phase = phase;
            setLayout(new MigLayout("insets 2 10 2 10", "[]", "center"));
            setOpaque(false);
            putClientProperty(FlatClientProperties.STYLE, "arc: 12");
            
            textLabel = new JLabel(phase.getDisplayName());
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

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
            Color accentColor = UIManager.getColor("Component.accentColor");
            if (isActive) {
                setOpaque(true);
                setBackground(accentColor);
                textLabel.setForeground(UIManager.getColor("Component.accentForeground"));
            } else if (isCompleted) {
                setOpaque(false);
                textLabel.setForeground(UIManager.getColor("Label.foreground"));
            } else {
                setOpaque(false);
                textLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
        }
    }
}
