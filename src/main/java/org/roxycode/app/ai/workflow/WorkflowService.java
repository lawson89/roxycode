package org.roxycode.app.ai.workflow;

import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.AgentDoc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@AgentService(value = "workflowService", roles = {"*"})
@AgentDoc("Manages the current phase of the development workflow and handles phase transition requests.")
public class WorkflowService {
    private WorkflowPhase currentPhase = WorkflowPhase.EXPLORE;
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
            this.pendingPhase = null;
            notifyPhaseListeners();
        }
    }

    @AgentDoc("Requests a transition to a new workflow phase. This requires human approval.")
    public void requestPhaseTransition(WorkflowPhase nextPhase) {
        if (nextPhase != null && nextPhase != currentPhase) {
            this.pendingPhase = nextPhase;
            notifyRequestListeners();
        }
    }

    @AgentDoc("Requests a transition to a new workflow phase by name. This requires human approval.")
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

    @AgentDoc("Transitions to a new phase immediately. Note: Use requestPhaseTransition instead for normal workflow.")
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