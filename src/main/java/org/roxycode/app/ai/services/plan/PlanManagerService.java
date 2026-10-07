package org.roxycode.app.ai.services.plan;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.services.specs.FunctionalSpec;
import org.roxycode.app.ai.services.specs.TechnicalSpec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for managing project plans and specifications.
 */
@AgentService(value = "planManager", roles = {"*"})
@AgentDoc("Service for managing project plans and specifications.")
public class PlanManagerService {

    private FunctionalSpec currentFunctionalSpec;
    private TechnicalSpec currentTechnicalSpec;
    private final List<PlanUpdateListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Listener for plan updates.
     */
    public interface PlanUpdateListener {
        void onSpecsUpdated(FunctionalSpec functionalSpec, TechnicalSpec technicalSpec);
    }

    /**
     * Adds a listener for plan updates.
     * @param listener the listener to add
     */
    public void addListener(PlanUpdateListener listener) {
        listeners.add(listener);
    }

    /**
     * Submits a functional specification for the project.
     * @param spec the functional specification to submit
     * @return a success message
     */
    @AgentDoc("Submits a functional specification for the project.")
    public String submitFunctionalSpec(
            @AgentDoc("The functional specification to submit.") FunctionalSpec spec) {
        this.currentFunctionalSpec = spec;
        notifyListeners();
        return "Functional spec submitted successfully.";
    }

    /**
     * Submits a functional specification for the project via simple arguments.
     * @param title the title of the project
     * @param goal the high-level goal
     * @param requirements list of functional requirements
     * @return a success message
     */
    @AgentDoc("Submits a functional specification for the project via simple arguments.")
    public String submitFunctionalSpec(
            @AgentDoc("The title of the project.") String title,
            @AgentDoc("The high-level goal.") String goal,
            @AgentDoc("List of functional requirements.") Object requirements) {
        return submitFunctionalSpec(new FunctionalSpec(title, goal, extractStringList(requirements)));
    }

    /**
     * Submits a functional specification for the project via a Map.
     * @param specMap Map containing title, goal, and requirements
     * @return a success message
     */
    @SuppressWarnings("unchecked")
    @AgentDoc("Submits a functional specification for the project via a Map.")
    public String submitFunctionalSpec(
            @AgentDoc("Map containing title, goal, and requirements.") Map<String, Object> specMap) {
        String title = (String) specMap.getOrDefault("title", "");
        String goal = (String) specMap.getOrDefault("goal", "");
        List<String> requirements = extractStringList(specMap.get("requirements"));
        return submitFunctionalSpec(new FunctionalSpec(title, goal, requirements));
    }

    /**
     * Submits a technical specification for the project.
     * @param spec the technical specification to submit
     * @return a success message
     */
    @AgentDoc("Submits a technical specification for the project.")
    public String submitTechnicalSpec(
            @AgentDoc("The technical specification to submit.") TechnicalSpec spec) {
        this.currentTechnicalSpec = spec;
        notifyListeners();
        return "Technical spec submitted successfully.";
    }

    /**
     * Submits a technical specification for the project via simple arguments.
     * @param architectureGoal the architectural goal
     * @param constraints list of technical constraints
     * @param implementationSteps list of implementation steps
     * @return a success message
     */
    @AgentDoc("Submits a technical specification for the project via simple arguments.")
    public String submitTechnicalSpec(
            @AgentDoc("The architectural goal.") String architectureGoal,
            @AgentDoc("List of technical constraints.") Object constraints,
            @AgentDoc("List of implementation steps.") Object implementationSteps) {
        return submitTechnicalSpec(new TechnicalSpec(architectureGoal, extractStringList(constraints), extractStringList(implementationSteps)));
    }

    /**
     * Submits a technical specification for the project via a Map.
     * @param specMap Map containing architectureGoal, constraints, and implementationSteps
     * @return a success message
     */
    @SuppressWarnings("unchecked")
    @AgentDoc("Submits a technical specification for the project via a Map.")
    public String submitTechnicalSpec(
            @AgentDoc("Map containing architectureGoal, constraints, and implementationSteps.") Map<String, Object> specMap) {
        String architectureGoal = (String) specMap.getOrDefault("architectureGoal", "");
        List<String> constraints = extractStringList(specMap.get("constraints"));
        List<String> implementationSteps = extractStringList(specMap.get("implementationSteps"));
        return submitTechnicalSpec(new TechnicalSpec(architectureGoal, constraints, implementationSteps));
    }

    /**
     * Gets the current functional specification.
     * @return the functional specification, or null if none submitted
     */
    public FunctionalSpec getCurrentFunctionalSpec() {
        return currentFunctionalSpec;
    }

    /**
     * Gets the current technical specification.
     * @return the technical specification, or null if none submitted
     */
    public TechnicalSpec getCurrentTechnicalSpec() {
        return currentTechnicalSpec;
    }

    /**
     * Clears all existing specifications.
     */
    public void clearSpecs() {
        this.currentFunctionalSpec = null;
        this.currentTechnicalSpec = null;
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
            listener.onSpecsUpdated(currentFunctionalSpec, currentTechnicalSpec);
        }
    }
}
