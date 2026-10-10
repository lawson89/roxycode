package org.roxycode.app.ai.services;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.model.ImplementationPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Service for managing project plans and specifications.
 */
@AgentService(value = "planManagerService", phases = {"*"})
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
        if (plan == null) {
            return "Error: Plan cannot be null.";
        }

        List<ImplementationPlan.TechStep> steps = new ArrayList<>(plan.technicalSteps());
        
        // 1. Auto-append mandatory compile step
        boolean hasCompile = steps.stream()
                .anyMatch(s -> s.description().toLowerCase().contains("compile"));
        if (!hasCompile) {
            steps.add(new ImplementationPlan.TechStep("Code compiles successfully", false));
        }

        // 2. Auto-append mandatory test step
        boolean hasTests = steps.stream()
                .anyMatch(s -> s.description().toLowerCase().contains("unit test") 
                            || s.description().toLowerCase().contains("tests pass"));
        if (!hasTests) {
            steps.add(new ImplementationPlan.TechStep("Unit tests pass", false));
        }

        // 3. Auto-append mandatory code review step
        boolean hasReview = steps.stream()
                .anyMatch(s -> s.description().toLowerCase().contains("code review"));
        if (!hasReview) {
            steps.add(new ImplementationPlan.TechStep("Automated code review passes", false));
        }

        this.currentPlan = new ImplementationPlan(
            plan.title(), 
            plan.goal(), 
            plan.requirements(), 
            steps,
            plan.userApproved(),
            plan.approvalTimestamp(),
            plan.approvedBy(),
            plan.completedOn()
        );
        notifyListeners();
        return "Implementation plan submitted successfully with mandatory verification checklist items.";
    }

    /**
     * Submits an implementation plan for the project via simple arguments.
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
                
        return submitPlan(new ImplementationPlan(title, goal, rawRequirements, steps, false, null, null, null));
    }

    /**
     * Submits an implementation plan for the project via a Map.
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
                
        return submitPlan(new ImplementationPlan(title, goal, requirements, steps, false, null, null, null));
    }

    /**
     * Approves the current plan.
     */
    @AgentDoc("Approves the current implementation plan.")
    public void approvePlan(String user, String timestamp) {
        if (currentPlan == null) return;
        currentPlan = new ImplementationPlan(
            currentPlan.title(),
            currentPlan.goal(),
            currentPlan.requirements(),
            currentPlan.technicalSteps(),
            true,
            timestamp,
            user,
            currentPlan.completedOn()
        );
        notifyListeners();
    }

    /**
     * Marks the current plan as completed.
     */
    @AgentDoc("Marks the current implementation plan as completed.")
    public void completePlan(String timestamp) {
        if (currentPlan == null) return;
        currentPlan = new ImplementationPlan(
            currentPlan.title(),
            currentPlan.goal(),
            currentPlan.requirements(),
            currentPlan.technicalSteps(),
            currentPlan.userApproved(),
            currentPlan.approvalTimestamp(),
            currentPlan.approvedBy(),
            timestamp
        );
        notifyListeners();
    }

    /**
     * Marks a technical implementation step as completed.
     */
    @AgentDoc("Marks a technical implementation step as completed.")
    public void markStepCompleted(@AgentDoc("The index of the step (0-based).") int index) {
        updateStepStatus(index, true);
    }

    /**
     * Marks a technical implementation step as incomplete.
     */
    @AgentDoc("Marks a technical implementation step as incomplete.")
    public void markStepIncomplete(@AgentDoc("The index of the step (0-based).") int index) {
        updateStepStatus(index, false);
    }

    private void updateStepStatus(int index, boolean completed) {
        if (currentPlan == null || index < 0 || index >= currentPlan.technicalSteps().size()) {
            return;
        }
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
        ImplementationPlan.TechStep step = steps.get(index);
        steps.set(index, new ImplementationPlan.TechStep(step.description(), completed));
        currentPlan = new ImplementationPlan(
            currentPlan.title(), 
            currentPlan.goal(), 
            currentPlan.requirements(), 
            steps,
            currentPlan.userApproved(),
            currentPlan.approvalTimestamp(),
            currentPlan.approvedBy(),
            currentPlan.completedOn()
        );
        notifyListeners();
    }

    /**
     * Gets the current implementation plan.
     */
    public ImplementationPlan getCurrentPlan() {
        return currentPlan;
    }

    /**
     * Clears the existing plan.
     */
    @AgentDoc("Clears the current implementation plan.")
    public void clearPlan() {
        this.currentPlan = null;
        notifyListeners();
    }

    public void clearSpecs() {
        clearPlan();
    }

    private List<String> extractStringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
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
