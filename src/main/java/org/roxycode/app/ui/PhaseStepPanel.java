package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;

import org.roxycode.app.model.ImplementationPlan;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.*;
import java.util.Locale;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A UI component that visualizes the workflow phases and tracks current progress.
 */
@SuppressWarnings({"PMD.ConstructorCallsOverridableMethod", "PMD.NonSerializableClass"})
public final class PhaseStepPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();

    private JPanel progressTracker;
    private final WorkflowService workflowService;
    private final PlanService planManagerService;
    private JLabel planLabel;

    public PhaseStepPanel(WorkflowService workflowService, PlanService planManagerService) {
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
            planLabel.setText(plan.title().toUpperCase(Locale.ROOT));
            planLabel.setVisible(true);
        }
    }

    private void updateActivePhase(WorkflowPhase currentPhase) {
        updatePlanDisplay(currentPhase);

        progressTracker.removeAll();
        java.util.Set<WorkflowPhase> visited = workflowService.getVisitedPhases();
        for (WorkflowPhaseIndicator indicator : indicators) {
            if (indicator.getPhase() == WorkflowPhase.EXPLORE) continue;
            indicator.setActive(indicator.getPhase() == currentPhase);
            indicator.setCompleted(visited.contains(indicator.getPhase()));
            progressTracker.add(indicator);
        }
        progressTracker.setVisible(currentPhase != WorkflowPhase.EXPLORE);
        revalidate();
        repaint();
    }
}