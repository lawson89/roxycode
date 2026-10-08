package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.model.ImplementationPlan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for managing project plans and specifications.
 */
@AgentService(value = "planManagerService", roles = {"*"})
@AgentDoc("Service for managing project plans and specifications.")
public class PlanManagerService {

    private ImplementationPlan currentPlan;
    private final List<PlanUpdateListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Listener for plan updates.
     */
    public interface PlanUpdateListener {
        void onPlanUpdated(ImplementationPlan plan);
    }

    /**
     * Adds a listener for plan updates.
     * @param listener the listener to add
     */
    public void addListener(PlanUpdateListener listener) {
        listeners.add(listener);
    }

    /**
     * Submits an implementation plan for the project.
     * @param plan the implementation plan to submit
     * @return a success message
     */
    @AgentDoc("Submits an implementation plan for the project.")
    public String submitPlan(
            @AgentDoc("The implementation plan to submit.") ImplementationPlan plan) {
        this.currentPlan = plan;
        notifyListeners();
        return "Implementation plan submitted successfully.";
    }

    /**
     * Submits an implementation plan for the project via simple arguments.
     * @param title the title of the project
     * @param goal the high-level goal
     * @param requirements list of functional requirements
     * @param technicalSteps list of technical implementation steps
     * @return a success message
     */
    @AgentDoc("Submits an implementation plan for the project via simple arguments.")
    public String submitPlan(
            @AgentDoc("The title of the project.") String title,
            @AgentDoc("The high-level goal.") String goal,
            @AgentDoc("List of functional requirements.") Object requirements,
            @AgentDoc("List of technical implementation steps.") Object technicalSteps) {
        return submitPlan(new ImplementationPlan(title, goal, extractStringList(requirements), extractStringList(technicalSteps)));
    }

    /**
     * Submits an implementation plan for the project via a Map.
     * @param planMap Map containing title, goal, requirements, and technicalSteps
     * @return a success message
     */
    @SuppressWarnings("unchecked")
    @AgentDoc("Submits an implementation plan for the project via a Map.")
    public String submitPlan(
            @AgentDoc("Map containing title, goal, requirements, and technicalSteps.") Map<String, Object> planMap) {
        String title = (String) planMap.getOrDefault("title", "");
        String goal = (String) planMap.getOrDefault("goal", "");
        List<String> requirements = extractStringList(planMap.get("requirements"));
        List<String> technicalSteps = extractStringList(planMap.get("technicalSteps"));
        return submitPlan(new ImplementationPlan(title, goal, requirements, technicalSteps));
    }

    /**
     * Gets the current implementation plan.
     * @return the implementation plan, or null if none submitted
     */
    public ImplementationPlan getCurrentPlan() {
        return currentPlan;
    }

    /**
     * Clears the existing plan.
     */
    public void clearSpecs() {
        this.currentPlan = null;
        notifyListeners();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        if (obj instanceof String[]) {
            return List.of((String[]) obj);
        }
        if (obj instanceof Object[]) {
            Object[] arr = (Object[]) obj;
            List<String> list = new java.util.ArrayList<>();
            for (Object o : arr) {
                if (o != null) {
                    list.add(o.toString());
                }
            }
            return list;
        }
        return List.of(obj.toString());
    }

    private void notifyListeners() {
        for (PlanUpdateListener listener : listeners) {
            listener.onPlanUpdated(currentPlan);
        }
    }
}