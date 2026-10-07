package org.roxycode.app.ai.workflow;

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


    @AgentDoc("Routes the workflow to the specified phase. Backward transitions (e.g., from DESIGN back to EXPLORE) are automatic and do not require specs. Forward transitions may require specifications (e.g., Functional Spec for DESIGN, Technical Spec for DEVELOPMENT) and some (DEVELOPMENT, VERIFICATION) always require human approval.")
    public String routeToPhase(String phaseName) {
        WorkflowPhase nextPhase;
        try {
            nextPhase = WorkflowPhase.valueOf(phaseName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Error: Invalid phase name: " + phaseName + ". Valid phases are: EXPLORE, DISCOVERY, DESIGN, DEVELOPMENT, VERIFICATION.";
        }

        if (nextPhase == currentPhase) {
            return "Already in phase: " + nextPhase;
        }

        boolean isForward = nextPhase.ordinal() > currentPhase.ordinal();

        if (isForward) {
            // Guardrails for forward movement
            if (nextPhase == WorkflowPhase.DESIGN && planManager.getCurrentFunctionalSpec() == null) {
                return "Error: Cannot advance to DESIGN. Functional Specification is missing. Call planManager.submitFunctionalSpec() first.";
            }
            if (nextPhase == WorkflowPhase.DEVELOPMENT && planManager.getCurrentTechnicalSpec() == null) {
                return "Error: Cannot advance to DEVELOPMENT. Technical Specification is missing. Call planManager.submitTechnicalSpec() first.";
            }

            // Approval routing for forward movement
            if (nextPhase == WorkflowPhase.DEVELOPMENT || nextPhase == WorkflowPhase.VERIFICATION) {
                requestPhaseTransition(nextPhase);
                return "Transition to " + nextPhase + " requested. Awaiting human approval.";
            }
        }

        // Backward movement or forward movement not requiring approval
        setCurrentPhase(nextPhase);
        String direction = isForward ? "Advanced" : "Routed back";
        return direction + " to phase: " + nextPhase;
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
}