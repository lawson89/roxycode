package org.roxycode.app.ai;

import org.roxycode.app.ai.services.PlanManagerService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.text.StringEscapeUtils;

@AgentService(value = "workflowService", roles = {"*"})
@AgentDoc("Manages the current phase of the development workflow and handles single-step phase transitions.")
public class WorkflowService {

    private final PlanManagerService planManager;
    private WorkflowPhase currentPhase = WorkflowPhase.EXPLORE;
    private final Set<WorkflowPhase> visitedPhases = new HashSet<>(Collections.singletonList(WorkflowPhase.EXPLORE));
    private WorkflowPhase pendingPhase;
    private String pendingReason;

    private final List<Consumer<WorkflowPhase>> phaseListeners = new ArrayList<>();
    private final List<TransitionRequestListener> requestListeners = new ArrayList<>();

    public interface TransitionRequestListener {

        void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested, String reason);
    }

    public WorkflowService(PlanManagerService planManager) {
        this.planManager = planManager;
    }

    @AgentDoc("Requests a single-step transition to an adjacent phase with a descriptive reason.")
    public String routeToPhase(
            @AgentDoc("The target phase: EXPLORE, PLANNING, DEVELOPMENT, or VERIFICATION.") String phaseName,
            @AgentDoc("The detailed reason for requesting this transition.") String reason) {

        WorkflowPhase nextPhase;
        try {
            nextPhase = WorkflowPhase.valueOf(phaseName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Error: Invalid phase name: " + phaseName + ". Valid phases are: EXPLORE, PLANNING, DEVELOPMENT, VERIFICATION.";
        }

        if (nextPhase == currentPhase) {
            return "Already in phase: " + nextPhase;
        }

        if (reason == null || reason.isBlank()) {
            return "Error: You must provide a descriptive 'reason' for requesting a phase transition.";
        }

        // Enforce strict single-step adjacent transitions
        if (!isAdjacent(currentPhase, nextPhase)) {
            return "Error: Cannot jump from " + currentPhase + " to " + nextPhase
                    + ". Only single-step transitions to adjacent phases are allowed.";
        }

        // Check prerequisites for specific transitions
        if (currentPhase == WorkflowPhase.PLANNING && nextPhase == WorkflowPhase.DEVELOPMENT) {
            if (planManager.getCurrentPlan() == null) {
                return "Error: Cannot advance to DEVELOPMENT. Implementation Plan is missing. Call planManagerService.submitPlan() first.";
            }
        }

        // Set pending state and notify listeners
        this.pendingPhase = nextPhase;
        this.pendingReason = reason;
        notifyRequestListeners();

        String summary = formatPlanSummary(planManager.getCurrentPlan());
        String message = "**Transition Request (" + currentPhase.getDisplayName() + " → " + nextPhase.getDisplayName() + ")**\n\n"
                + "**Reason:** " + reason;

        // Yield execution turn immediately for user approval
        String json = String.format("{\"summary\": \"%s\", \"message\": \"%s\", \"reason\": \"%s\"}",
                StringEscapeUtils.escapeJson(summary),
                StringEscapeUtils.escapeJson(message),
                StringEscapeUtils.escapeJson(reason));

        throw new YieldTurnException(json);
    }

    /**
     * Helper to verify if two phases are adjacent in the single-step state
     * machine.
     */
    private boolean isAdjacent(WorkflowPhase current, WorkflowPhase next) {
        switch (current) {
            case EXPLORE:
                return next == WorkflowPhase.PLANNING;
            case PLANNING:
                return next == WorkflowPhase.EXPLORE || next == WorkflowPhase.DEVELOPMENT;
            case DEVELOPMENT:
                return next == WorkflowPhase.PLANNING || next == WorkflowPhase.VERIFICATION;
            case VERIFICATION:
                return next == WorkflowPhase.DEVELOPMENT || next == WorkflowPhase.EXPLORE;
            default:
                return false;
        }
    }

    public WorkflowPhase getCurrentPhase() {
        return currentPhase;
    }

    public WorkflowPhase getPendingPhase() {
        return pendingPhase;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void approveTransition() {
        if (pendingPhase != null) {
            this.currentPhase = pendingPhase;
            this.visitedPhases.add(pendingPhase);
            this.pendingPhase = null;
            this.pendingReason = null;
            notifyPhaseListeners();
        }
    }

    public void rejectTransition() {
        this.pendingPhase = null;
        this.pendingReason = null;
        notifyRequestListeners();
    }

    public Set<WorkflowPhase> getVisitedPhases() {
        return Collections.unmodifiableSet(visitedPhases);
    }

    public void resetWorkflow() {
        this.pendingPhase = null;
        this.pendingReason = null;
        this.currentPhase = WorkflowPhase.EXPLORE;
        this.visitedPhases.clear();
        this.visitedPhases.add(WorkflowPhase.EXPLORE);
        this.planManager.clearSpecs();
        notifyPhaseListeners();
        notifyRequestListeners();
    }

    public void addPhaseListener(Consumer<WorkflowPhase> listener) {
        phaseListeners.add(listener);
        listener.accept(currentPhase);
    }

    public void addTransitionRequestListener(TransitionRequestListener listener) {
        requestListeners.add(listener);
    }

    private void notifyPhaseListeners() {
        for (Consumer<WorkflowPhase> listener : phaseListeners) {
            listener.accept(currentPhase);
        }
    }

    private void notifyRequestListeners() {
        for (TransitionRequestListener listener : requestListeners) {
            listener.onTransitionRequested(currentPhase, pendingPhase, pendingReason);
        }
    }

    private String formatPlanSummary(org.roxycode.app.model.ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("PLAN SUMMARY:\n\n### ").append(plan.title().trim()).append("\n");
        sb.append("**Goal:** ").append(plan.goal().replaceAll("\s+", " ").trim()).append("\n\n");

        if (!plan.requirements().isEmpty()) {
            sb.append("#### Requirements\n");
            for (String req : plan.requirements()) {
                sb.append("* ").append(req.replaceAll("\s+", " ").trim()).append("\n");
            }
            sb.append("\n");
        }

        if (!plan.technicalSteps().isEmpty()) {
            sb.append("#### Technical Steps\n");
            for (org.roxycode.app.model.ImplementationPlan.TechStep step : plan.technicalSteps()) {
                sb.append("* ").append(step.description().replaceAll("\s+", " ").trim()).append("\n");
            }
        }
        return sb.toString();
    }
}
