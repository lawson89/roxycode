package org.roxycode.app.ai.workflow;

import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.AgentDoc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@AgentService(value = "workflowService", roles = {"*"})
@AgentDoc("Manages the current phase of the development workflow.")
public class WorkflowService {
    private WorkflowPhase currentPhase = WorkflowPhase.EXPLORE;
    private final List<Consumer<WorkflowPhase>> listeners = new ArrayList<>();

    @AgentDoc("Gets the current workflow phase.")
    public WorkflowPhase getCurrentPhase() {
        return currentPhase;
    }

    @AgentDoc("Sets the current workflow phase and notifies listeners.")
    public void setCurrentPhase(WorkflowPhase phase) {
        if (this.currentPhase != phase) {
            this.currentPhase = phase;
            notifyListeners();
        }
    }

    @AgentDoc("Sets the current workflow phase by name (e.g., 'DEVELOPMENT').")
    public void setPhaseByName(String phaseName) {
        try {
            setCurrentPhase(WorkflowPhase.valueOf(phaseName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            // Ignored for now
        }
    }

    public void addPhaseListener(Consumer<WorkflowPhase> listener) {
        listeners.add(listener);
        listener.accept(currentPhase);
    }

    private void notifyListeners() {
        for (Consumer<WorkflowPhase> listener : listeners) {
            listener.accept(currentPhase);
        }
    }
}