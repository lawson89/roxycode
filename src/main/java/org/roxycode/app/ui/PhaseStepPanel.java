package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.ai.WorkflowMode;
import org.roxycode.app.model.ImplementationPlan;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A UI component that visualizes the workflow phases and tracks current progress.
 */
public class PhaseStepPanel extends JPanel {

    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();
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
        add(progressTracker, "hidemode 3");

        WorkflowPhase[] phases = WorkflowPhase.values();
        for (WorkflowPhase phase : phases) {
            indicators.add(new WorkflowPhaseIndicator(phase, "font: -1"));
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
        if (currentPhase.getMode() == WorkflowMode.CHANGE) {
            java.util.Set<WorkflowPhase> visited = workflowService.getVisitedPhases();
            for (WorkflowPhaseIndicator indicator : indicators) {
                if (indicator.getPhase().getMode() == WorkflowMode.CHANGE) {
                    indicator.setActive(indicator.getPhase() == currentPhase);
                    indicator.setCompleted(visited.contains(indicator.getPhase()));
                    progressTracker.add(indicator);
                }
            }
            progressTracker.setVisible(true);
        } else {
            progressTracker.setVisible(false);
            if (modeBadge != null) modeBadge.setVisible(true);
        }
        revalidate();
        repaint();
    }
}
