package org.roxycode.app.ai.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.Strings;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.model.ImplementationPlan;

/** Service for managing project plans and specifications. */
@AgentService(
        value = "planService",
        phases = {"PLAN", "CODE", "REVIEW"})
@AgentDoc("Service for managing project plans and specifications.")
public class PlanService {

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private final WorkflowService workflowService;

    /** Listener for plan updates. */
    @FunctionalInterface
    public interface PlanUpdateListener {
        void onPlanUpdated(ImplementationPlan plan);
    }

    public PlanService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * Adds a listener for plan updates.
     *
     * @param listener the listener to add
     */
    public void addListener(PlanUpdateListener listener) {
        workflowService.addPlanListener(listener::onPlanUpdated);
    }

    /**
     * Submits an implementation plan for the project.
     *
     * @param plan the implementation plan to submit
     * @return a success message
     */
    public String submitPlan(ImplementationPlan plan) {
        requirePhase("submitPlan", WorkflowPhase.PLAN);
        if (plan == null) {
            return "Error: Plan cannot be null.";
        }

        List<ImplementationPlan.TechStep> steps = new ArrayList<>(plan.technicalSteps());

        boolean hasCompile = false;
        boolean hasTests = false;
        boolean hasReview = false;
        for (ImplementationPlan.TechStep s : steps) {
            String desc = s.description().toLowerCase(Locale.ROOT);
            if (desc.contains("compile")) {
                hasCompile = true;
            }
            if (desc.contains("unit test") || desc.contains("tests pass")) {
                hasTests = true;
            }
            if (desc.contains("code review")) {
                hasReview = true;
            }
        }

        // 1. Auto-append mandatory compile step
        if (!hasCompile) {
            steps.add(new ImplementationPlan.TechStep("Code compiles successfully", false));
        }

        // 2. Auto-append mandatory test step
        if (!hasTests) {
            steps.add(new ImplementationPlan.TechStep("Unit tests pass", false));
        }

        // 3. Auto-append mandatory code review step
        if (!hasReview) {
            steps.add(new ImplementationPlan.TechStep("Automated code review passes", false));
        }

        workflowService.setCurrentPlan(plan.withTechnicalSteps(steps));
        return "Implementation plan submitted successfully with mandatory verification checklist items.";
    }

    /**
     * Submits a project plan covering both functional and technical specifications via positional
     * arguments.
     */
    @AgentDoc(
            value =
                    "Submits a project plan covering both functional and technical specifications via positional arguments.",
            examples =
                    "planService.submitPlan('Auth Service', 'Implement JWT', ['Login', 'Logout'], ['Create entity', 'Setup filter'])")
    public String submitPlan(
            @AgentDoc("The title of the project.") String title,
            @AgentDoc("The high-level goal.") String goal,
            @AgentDoc("List of functional requirements (String array or list).")
                    Object requirements,
            @AgentDoc("List of technical implementation steps (String array or list).")
                    Object technicalSteps) {
        requirePhase("submitPlan", WorkflowPhase.PLAN);
        List<String> rawRequirements = extractStringList(requirements);
        List<String> rawTechSteps = extractStringList(technicalSteps);

        List<ImplementationPlan.TechStep> steps = toTechSteps(rawTechSteps);

        return submitPlan(
                new ImplementationPlan(
                        title, goal, rawRequirements, steps, false, null, null, null, null));
    }

    /**
     * Submits a project plan covering both functional and technical specifications via a Map
     * literal.
     */
    @SuppressWarnings("unchecked")
    @AgentDoc(
            value =
                    "Submits a project plan covering both functional and technical specifications via a Map literal.",
            examples =
                    "planService.submitPlan({'title': 'Auth Service', 'goal': 'Implement JWT', 'requirements': ['Login', 'Logout'], 'technicalSteps': ['Create entity', 'Setup filter']})")
    public String submitPlan(
            @AgentDoc("Map containing title, goal, requirements, and technicalSteps.")
                    Map<String, Object> planMap) {
        requirePhase("submitPlan", WorkflowPhase.PLAN);
        String title = (String) planMap.getOrDefault("title", "");
        String goal = (String) planMap.getOrDefault("goal", "");
        List<String> requirements = extractStringList(planMap.get("requirements"));
        List<String> rawTechSteps = extractStringList(planMap.get("technicalSteps"));

        List<ImplementationPlan.TechStep> steps = toTechSteps(rawTechSteps);

        return submitPlan(
                new ImplementationPlan(
                        title, goal, requirements, steps, false, null, null, null, null));
    }

    private List<ImplementationPlan.TechStep> toTechSteps(List<String> rawTechSteps) {
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(rawTechSteps.size());
        for (String s : rawTechSteps) {
            steps.add(new ImplementationPlan.TechStep(s, false));
        }
        return steps;
    }

    /** Approves the current plan. Internal lifecycle method; cannot be invoked from JEXL. */
    public void approvePlan(String user, String timestamp) {
        checkNotJexlCaller("approvePlan");
        workflowService.approvePlan(user, timestamp);
    }

    /**
     * Marks the current plan as completed. Internal lifecycle method; cannot be invoked from JEXL.
     */
    public void completePlan(String timestamp) {
        checkNotJexlCaller("completePlan");
        workflowService.completePlan(timestamp);
    }

    /** Marks a specific technical implementation step as completed using its 0-based index. */
    @AgentDoc(
            value =
                    "Marks a specific technical implementation step as completed using its 0-based index.",
            examples = "planService.markStepCompleted(0)")
    public void markStepCompleted(
            @AgentDoc("The index of the step (0-based). index must be valid for the current plan.")
                    int index) {
        requirePhase("markStepCompleted", WorkflowPhase.CODE, WorkflowPhase.REVIEW);
        updateStepStatus(index, true);
    }

    /** Marks a technical implementation step as incomplete. */
    @AgentDoc(
            value = "Marks a technical implementation step as incomplete.",
            examples = "planService.markStepIncomplete(0)")
    public void markStepIncomplete(@AgentDoc("The index of the step (0-based).") int index) {
        requirePhase("markStepIncomplete", WorkflowPhase.CODE, WorkflowPhase.REVIEW);
        updateStepStatus(index, false);
    }

    private void updateStepStatus(int index, boolean completed) {
        ImplementationPlan currentPlan = workflowService.getCurrentPlan();
        if (currentPlan == null || index < 0 || index >= currentPlan.technicalSteps().size()) {
            return;
        }
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
        ImplementationPlan.TechStep step = steps.get(index);
        steps.set(index, new ImplementationPlan.TechStep(step.description(), completed));
        workflowService.setCurrentPlan(currentPlan.withTechnicalSteps(steps));
    }

    /** Gets the current implementation plan. */
    public ImplementationPlan getCurrentPlan() {
        return workflowService.getCurrentPlan();
    }

    /** Clears the existing plan. Internal lifecycle method; cannot be invoked from JEXL. */
    public void clearPlan() {
        checkNotJexlCaller("clearPlan");
        workflowService.clearPlan();
    }

    /** Transitions the workflow to the development (CODE) phase. */
    @AgentDoc(
            value = "Requests transition to the development (CODE) phase.",
            examples = "planService.requestDevelopment('Ready to start coding.')")
    public String requestDevelopment(String reason) {
        requirePhase("requestDevelopment", WorkflowPhase.PLAN);
        return workflowService.routeToPhase("CODE", reason);
    }

    /** Transitions the workflow to the review phase. */
    @AgentDoc(
            value = "Requests transition to the review phase.",
            examples = "planService.requestReview('Feature implemented, needs review.')")
    public String requestReview(String reason) {
        requirePhase("requestReview", WorkflowPhase.CODE);
        return workflowService.routeToPhase("REVIEW", reason);
    }

    /** Transitions the workflow back to development (CODE) to address fixes. */
    @AgentDoc(
            value = "Requests transition back to development to address fixes.",
            examples = "planService.requestFixes('Found a bug in auth logic.')")
    public String requestFixes(String reason) {
        requirePhase("requestFixes", WorkflowPhase.REVIEW);
        return workflowService.routeToPhase("CODE", reason);
    }

    /** Signals completion of the current cycle and returns to planning. */
    @AgentDoc(
            value = "Signals completion of the current cycle and returns to planning.",
            examples = "planService.requestCompletion('All steps verified, project ready.')")
    public String requestCompletion(String reason) {
        requirePhase("requestCompletion", WorkflowPhase.REVIEW);
        return workflowService.routeToPhase("PLAN", reason);
    }

    /** Transitions the workflow back to planning (PLAN) to address technical blockers. */
    @AgentDoc(
            value =
                    "Requests transition back to the planning phase (PLAN) due to technical blockers or needed revisions.",
            examples =
                    "planService.requestPlanRevision('Blocked by missing API, need to update the architecture.')")
    public String requestPlanRevision(String reason) {
        requirePhase("requestPlanRevision", WorkflowPhase.CODE);
        return workflowService.routeToPhase("PLAN", reason);
    }

    private void requirePhase(String methodName, WorkflowPhase... allowedPhases) {
        if (workflowService == null) {
            return;
        }
        WorkflowPhase current = workflowService.getCurrentPhase();
        for (WorkflowPhase allowed : allowedPhases) {
            if (current == allowed) {
                return;
            }
        }
        throw new IllegalStateException(
                "Method '"
                        + methodName
                        + "' is only allowed in phase(s) "
                        + Arrays.toString(allowedPhases)
                        + ". Current phase: "
                        + current);
    }

    private void checkNotJexlCaller(String methodName) {
        boolean calledDirectlyFromJexl =
                STACK_WALKER.walk(
                        frames -> {
                            for (StackWalker.StackFrame frame :
                                    (Iterable<StackWalker.StackFrame>) frames::iterator) {
                                String className = frame.getClassName();
                                if (className.equals(WorkflowService.class.getName())) {
                                    return false;
                                }
                                if (Strings.CS.startsWith(className, "org.apache.commons.jexl3.")
                                        || Strings.CS.equals(
                                                className, "org.roxycode.app.ai.JexlTool")) {
                                    return true;
                                }
                            }
                            return false;
                        });
        if (calledDirectlyFromJexl) {
            throw new IllegalStateException(
                    "Method '"
                            + methodName
                            + "' is an internal lifecycle operation and cannot be invoked from JEXL.");
        }
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

    /** Adds an entry to the handoff log. Internal lifecycle method; cannot be invoked from JEXL. */
    public void addHandoffLogEntry(String timestamp, String user, String message) {
        checkNotJexlCaller("addHandoffLogEntry");
        workflowService.addHandoffLogEntry(timestamp, user, message);
    }

    /** Adds an entry to the handoff log. */
    @AgentDoc(
            value = "Adds a high-level summary entry to the handoff log.",
            examples = "planService.addHandoffLogEntry('Updated the README.')")
    public void addHandoffLogEntry(String entry) {
        requirePhase(
                "addHandoffLogEntry", WorkflowPhase.PLAN, WorkflowPhase.CODE, WorkflowPhase.REVIEW);
        workflowService.addHandoffLogEntry(java.time.OffsetDateTime.now().toString(), "System", entry);
    }
}
