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

    /**
     * Listener interface for phase transition requests.
     */
    public interface TransitionRequestListener {
        /**
         * Called when a transition to a new phase is requested.
         * @param current The current phase.
         * @param requested The requested next phase. (null if request was cancelled/rejected)
         */
        void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested);
    }


    /**
     * Routes the workflow to the specified phase. Transitions between EXPLORE and PLANNING are free.
     * Transition to DEVELOPMENT is only allowed from PLANNING (requires HIL approval and Implementation Plan) 
     * or from VERIFICATION (free). Transition to VERIFICATION is only allowed from DEVELOPMENT and is free.
     * Transition from VERIFICATION to EXPLORE requires HIL approval.
     * 
     * @param phaseName The name of the phase to route to.
     * @return A status message describing the result of the routing attempt.
     * @throws YieldTurnException if human approval is required for the requested transition.
     */
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

        // Backward transitions are always allowed, except from VERIFICATION to EXPLORE which requires sign-off
        if (nextPhase.ordinal() < currentPhase.ordinal() && !(currentPhase == WorkflowPhase.VERIFICATION && nextPhase == WorkflowPhase.EXPLORE)) {
            setCurrentPhase(nextPhase);
            return "Routed back to phase: " + nextPhase;
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
                throw new YieldTurnException(summary + "\n\n**Transition to DEVELOPMENT requested. Awaiting human approval.**");
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
            if (nextPhase == WorkflowPhase.EXPLORE) {
                requestPhaseTransition(nextPhase);
                String summary = formatPlanSummary(planManager.getCurrentPlan());
                throw new YieldTurnException(summary + "\n\n**Task completion and transition to EXPLORE requested. Awaiting human sign-off.**");
            }
            return "Error: Cannot transition from VERIFICATION to " + nextPhase + ".";
        }
        return "Error: Transition from " + currentPhase + " to " + nextPhase + " is not allowed.";
    }

    @AgentDoc("Gets the current workflow phase.")
    public WorkflowPhase getCurrentPhase() {
        return currentPhase;
    }

    @AgentDoc("Gets the currently pending workflow phase request, if any.")
    public WorkflowPhase getPendingPhase() {
        return pendingPhase;
    }

    /**
     * Sets the current workflow phase and notifies listeners.
     * @param phase The new phase to set.
     */
    public void setCurrentPhase(WorkflowPhase phase) {
        if (phase == null) {
            return;
        }
        if (this.currentPhase != phase) {
            this.currentPhase = phase;
            this.visitedPhases.add(phase);
            this.pendingPhase = null;
            notifyPhaseListeners();
        }
    }

    /**
     * Gets the set of phases that have been visited during this workflow session.
     * @return An unmodifiable set of visited phases.
     */
    public Set<WorkflowPhase> getVisitedPhases() {
        return Collections.unmodifiableSet(visitedPhases);
    }

    /**
     * Resets the entire workflow to the EXPLORE phase, clearing all specifications and history.
     */
    @AgentDoc("Resets the entire workflow to the EXPLORE phase, clearing all specifications and history. Use this for a full 'factory reset' of the project lifecycle.")
    public void resetWorkflow() {
        this.pendingPhase = null;
        this.currentPhase = WorkflowPhase.EXPLORE;
        this.visitedPhases.clear();
        this.visitedPhases.add(WorkflowPhase.EXPLORE);
        this.planManager.clearSpecs();
        notifyPhaseListeners();
        notifyRequestListeners();
    }

    public void requestPhaseTransition(WorkflowPhase nextPhase) {
        if (nextPhase != null && nextPhase != currentPhase) {
            this.pendingPhase = nextPhase;
            notifyRequestListeners();
        }
    }

    public void requestPhaseByName(String phaseName) {
        try {
            requestPhaseTransition(WorkflowPhase.valueOf(phaseName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            // Ignored
        }
    }

    /**
     * Approves the pending phase transition.
     */
    public void approveTransition() {
        if (pendingPhase != null) {
            setCurrentPhase(pendingPhase);
        }
    }

    /**
     * Rejects the pending phase transition.
     */
    public void rejectTransition() {
        this.pendingPhase = null;
        notifyRequestListeners();
    }

    public void transitionPhase(WorkflowPhase nextPhase, String summary) {
        setCurrentPhase(nextPhase);
    }

    /**
     * Adds a listener for phase changes.
     * @param listener The listener to add.
     */
    public void addPhaseListener(Consumer<WorkflowPhase> listener) {
        phaseListeners.add(listener);
        listener.accept(currentPhase);
    }

    /**
     * Adds a listener for transition requests.
     * @param listener The listener to add.
     */
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
            listener.onTransitionRequested(currentPhase, pendingPhase);
        }
    }

        private String formatPlanSummary(org.roxycode.app.model.ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // Start with a prefix to avoid triggering JSON parsing errors (e.g. starting with #)
        sb.append("PLAN SUMMARY:\n\n### 🤖 ").append(plan.title()).append("\n");
        sb.append("**Goal:** ").append(plan.goal()).append("\n\n");
        sb.append("#### 📋 Requirements\n");
        for (String req : plan.requirements()) {
            sb.append("* ").append(req).append("\n");
        }
        sb.append("\n#### 🛠️ Technical Steps\n");
        for (String step : plan.technicalSteps()) {
            sb.append("* ").append(step).append("\n");
        }
        return sb.toString();
    }

}