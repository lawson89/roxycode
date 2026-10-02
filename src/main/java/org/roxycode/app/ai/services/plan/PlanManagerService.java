package org.roxycode.app.ai.services.plan;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.services.specs.FunctionalSpec;
import org.roxycode.app.ai.services.specs.TechnicalSpec;

import java.util.List;
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

    private void notifyListeners() {
        for (PlanUpdateListener listener : listeners) {
            listener.onSpecsUpdated(currentFunctionalSpec, currentTechnicalSpec);
        }
    }
}
