package org.roxycode.app.ai;

import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.services.PlanManagerService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.text.StringEscapeUtils;

@AgentService(value = "workflowService", roles = {"*"})
@AgentDoc("Manages the current phase of the development workflow and handles phase transition requests.")
public class WorkflowService {
    private final PlanManagerService planManager;

    public WorkflowService(PlanManagerService planManager) {
        this.planManager = planManager;
    }
    private WorkflowPhase currentPhase = WorkflowPhase.EXPLORE;
    private final Set<WorkflowPhase> visitedPhases = new HashSet<>(Collections.singletonList(WorkflowPhase.EXPLORE));
    private WorkflowPhase pendingPhase;
    private final List<Consumer<WorkflowPhase>> phaseListeners = new ArrayList<>();
    private final List<TransitionRequestListener> requestListeners = new ArrayList<>();

    public interface TransitionRequestListener {
        void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested);
    }

    @AgentDoc("Routes the workflow to the specified phase according to strict transition rules.")
    public String routeToPhase(String phaseName) {
        WorkflowPhase nextPhase;
        try {
            nextPhase = WorkflowPhase.valueOf(phaseName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Error: Invalid phase name: " + phaseName + ". Valid phases are: EXPLORE, PLANNING, DEVELOPMENT, VERIFICATION.";
        }
        if (nextPhase == currentPhase) {
            return "Already in phase: " + nextPhase;
        }

        // Handle backward transitions (except to EXPLORE which is always gated)
        if (nextPhase.ordinal() < currentPhase.ordinal() && nextPhase != WorkflowPhase.EXPLORE) {
            setCurrentPhase(nextPhase);
            return "Routed back to phase: " + nextPhase;
        }

        // Any transition to EXPLORE from another phase is gated as "Task Completion" or "Reset"
        if (nextPhase == WorkflowPhase.EXPLORE) {
            requestPhaseTransition(nextPhase);
            String summary = formatPlanSummary(planManager.getCurrentPlan());
            String message = "**Transition to EXPLORE requested (Task Completion/Reset). Awaiting human sign-off.**";
            throw new YieldTurnException("{\"summary\": \"" + StringEscapeUtils.escapeJson(summary) + "\", \"message\": \"" + StringEscapeUtils.escapeJson(message) + "\"}");
        }

        if (currentPhase == WorkflowPhase.EXPLORE) {
            if (nextPhase == WorkflowPhase.PLANNING) {
                setCurrentPhase(nextPhase);
                return "Advanced to phase: " + nextPhase;
            }
            return "Error: Cannot transition from EXPLORE to " + nextPhase + ". You must go to PLANNING first.";
        }
        
        if (currentPhase == WorkflowPhase.PLANNING) {
            if (nextPhase == WorkflowPhase.DEVELOPMENT) {
                if (planManager.getCurrentPlan() == null) {
                    return "Error: Cannot advance to DEVELOPMENT. Implementation Plan is missing. Call planManagerService.submitPlan() first.";
                }
                requestPhaseTransition(nextPhase);
                String summary = formatPlanSummary(planManager.getCurrentPlan());
                String message = "**Transition to DEVELOPMENT requested. Awaiting human approval.**";
                throw new YieldTurnException("{\"summary\": \"" + StringEscapeUtils.escapeJson(summary) + "\", \"message\": \"" + StringEscapeUtils.escapeJson(message) + "\"}");
            }
            return "Error: Cannot transition from PLANNING to " + nextPhase + ".";
        }
        
        if (currentPhase == WorkflowPhase.DEVELOPMENT) {
            if (nextPhase == WorkflowPhase.VERIFICATION) {
                setCurrentPhase(nextPhase);
                return "Advanced to phase: " + nextPhase;
            }
            return "Error: Cannot transition from DEVELOPMENT to " + nextPhase + ". You must go to VERIFICATION next.";
        }
        
        if (currentPhase == WorkflowPhase.VERIFICATION) {
            // Forward transitions from VERIFICATION are not allowed
            return "Error: Cannot transition from VERIFICATION to " + nextPhase + ".";
        }

        return "Error: Transition from " + currentPhase + " to " + nextPhase + " is not allowed.";
    }

    public WorkflowPhase getCurrentPhase() { return currentPhase; }
    public WorkflowPhase getPendingPhase() { return pendingPhase; }

    void setCurrentPhase(WorkflowPhase phase) {
        if (phase != null && this.currentPhase != phase) {
            this.currentPhase = phase;
            this.visitedPhases.add(phase);
            this.pendingPhase = null;
            notifyPhaseListeners();
        }
    }

    public Set<WorkflowPhase> getVisitedPhases() { return Collections.unmodifiableSet(visitedPhases); }

    @AgentDoc("Resets the entire workflow to the EXPLORE phase, clearing all specifications and history.")
    public void resetWorkflow() {
        this.pendingPhase = null;
        this.currentPhase = WorkflowPhase.EXPLORE;
        this.visitedPhases.clear();
        this.visitedPhases.add(WorkflowPhase.EXPLORE);
        this.planManager.clearSpecs();
        notifyPhaseListeners();
        notifyRequestListeners();
    }

    void requestPhaseTransition(WorkflowPhase nextPhase) {
        if (nextPhase != null && nextPhase != currentPhase) {
            this.pendingPhase = nextPhase;
            notifyRequestListeners();
        }
    }

    void requestPhaseByName(String phaseName) {
        try { requestPhaseTransition(WorkflowPhase.valueOf(phaseName.toUpperCase())); } catch (IllegalArgumentException e) {}
    }

    public void approveTransition() {
        if (pendingPhase != null) setCurrentPhase(pendingPhase);
    }

    public void rejectTransition() {
        this.pendingPhase = null;
        notifyRequestListeners();
    }

    public void addPhaseListener(Consumer<WorkflowPhase> listener) {
        phaseListeners.add(listener);
        listener.accept(currentPhase);
    }

    public void addTransitionRequestListener(TransitionRequestListener listener) { requestListeners.add(listener); }

    private void notifyPhaseListeners() { for (Consumer<WorkflowPhase> listener : phaseListeners) listener.accept(currentPhase); }
    private void notifyRequestListeners() { for (TransitionRequestListener listener : requestListeners) listener.onTransitionRequested(currentPhase, pendingPhase); }

    private String formatPlanSummary(org.roxycode.app.model.ImplementationPlan plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("PLAN SUMMARY:\n\n### 🤖 ").append(plan.title()).append("\n");
        sb.append("**Goal:** ").append(plan.goal()).append("\n\n");
        sb.append("#### 📋 Requirements\n");
        for (String req : plan.requirements()) sb.append("* ").append(req).append("\n");
        sb.append("\n#### 🛠️ Technical Steps\n");
        for (String step : plan.technicalSteps()) sb.append("* ").append(step).append("\n");
        return sb.toString();
    }
}
