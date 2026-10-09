package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.model.ImplementationPlan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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
        List<String> rawRequirements = extractStringList(requirements);
        List<String> rawTechSteps = extractStringList(technicalSteps);
        
        List<ImplementationPlan.TechStep> steps = rawTechSteps.stream()
                .map(s -> new ImplementationPlan.TechStep(s, false))
                .collect(Collectors.toList());
                
        return submitPlan(new ImplementationPlan(title, goal, rawRequirements, steps));
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
        List<String> rawTechSteps = extractStringList(planMap.get("technicalSteps"));
        
        List<ImplementationPlan.TechStep> steps = rawTechSteps.stream()
                .map(s -> new ImplementationPlan.TechStep(s, false))
                .collect(Collectors.toList());
                
        return submitPlan(new ImplementationPlan(title, goal, requirements, steps));
    }

    /**
     * Marks a technical implementation step as completed.
     * @param index the index of the step (0-based)
     */
    @AgentDoc("Marks a technical implementation step as completed.")
    public void markStepCompleted(@AgentDoc("The index of the step (0-based).") int index) {
        updateStepStatus(index, true);
    }

    /**
     * Marks a technical implementation step as incomplete.
     * @param index the index of the step (0-based)
     */
    @AgentDoc("Marks a technical implementation step as incomplete.")
    public void markStepIncomplete(@AgentDoc("The index of the step (0-based).") int index) {
        updateStepStatus(index, false);
    }

    private void updateStepStatus(int index, boolean completed) {
        if (currentPlan == null || index < 0 || index >= currentPlan.technicalSteps().size()) {
            return;
        }
        java.util.List<ImplementationPlan.TechStep> steps = new java.util.ArrayList<>(currentPlan.technicalSteps());
        ImplementationPlan.TechStep step = steps.get(index);
        steps.set(index, new ImplementationPlan.TechStep(step.description(), completed));
        currentPlan = new ImplementationPlan(currentPlan.title(), currentPlan.goal(), currentPlan.requirements(), steps);
        notifyListeners();
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

        private List<String> extractStringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                result.add(extractString(item));
            }
        } else if (obj instanceof Object[]) {
            for (Object item : (Object[]) obj) {
                result.add(extractString(item));
            }
        } else {
            result.add(extractString(obj));
        }
        return result;
    }

    private String extractString(Object item) {
        if (item == null) return "";
        if (item instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) item;
            Object desc = map.get("description");
            if (desc == null) desc = map.get("text");
            return desc != null ? desc.toString() : item.toString();
        }
        return item.toString();
    }

    private void notifyListeners() {
        for (PlanUpdateListener listener : listeners) {
            listener.onPlanUpdated(currentPlan);
        }
    }
}