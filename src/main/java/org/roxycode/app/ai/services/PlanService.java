package org.roxycode.app.ai.services;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.TransitionRequestedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;

/** Unified service for managing project plans, specifications, and workflow phase transitions. */
@AgentService(
        value = "planService",
        phases = {"PLAN", "CODE", "REVIEW"})
@AgentDoc("Service for managing project plans and specifications.")
@SuppressWarnings("PMD.NullAssignment")
public class PlanService {

    private static final String PHASE_DEVELOP = "DEVELOP";
    private static final String PHASE_CODE = "CODE";

    /** Represents a validated workflow transition response that should return directly. */
    public record TransitionResult(String markdown) {
        @Override
        public String toString() {
            return markdown;
        }
    }

    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private ImplementationPlan currentPlan;
    private WorkflowPhase currentPhase = WorkflowPhase.PLAN;
    private final Set<WorkflowPhase> visitedPhases = EnumSet.of(WorkflowPhase.PLAN);
    private WorkflowPhase pendingPhase;
    private String pendingReason;

    public PlanService(
            EnvironmentService environmentService, ApplicationEventPublisher eventPublisher) {
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Submits an implementation plan for the project. Internal Java-only method; cannot be invoked
     * from JEXL.
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

        setCurrentPlan(plan.withTechnicalSteps(steps));
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
        if (currentPlan == null) {
            return;
        }
        currentPlan = currentPlan.withApproval(user, timestamp);
        publishPlanUpdated();
    }

    /**
     * Marks the current plan as completed. Internal lifecycle method; cannot be invoked from JEXL.
     */
    public void completePlan(String timestamp) {
        if (currentPlan == null) {
            return;
        }
        currentPlan = currentPlan.withCompletedOn(timestamp);
        publishPlanUpdated();
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
        if (currentPlan == null || index < 0 || index >= currentPlan.technicalSteps().size()) {
            return;
        }
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
        ImplementationPlan.TechStep step = steps.get(index);
        steps.set(index, new ImplementationPlan.TechStep(step.description(), completed));
        setCurrentPlan(currentPlan.withTechnicalSteps(steps));
    }

    /** Gets the current implementation plan. */
    public ImplementationPlan getCurrentPlan() {
        return currentPlan;
    }

    /** Sets the current implementation plan and publishes PlanUpdatedEvent. */
    public void setCurrentPlan(ImplementationPlan plan) {
        this.currentPlan = plan;
        publishPlanUpdated();
    }

    /** Clears the existing plan. Internal lifecycle method; cannot be invoked from JEXL. */
    public void clearPlan() {
        this.currentPlan = null;
        publishPlanUpdated();
    }

    /** Transitions the workflow to the development (CODE) phase. */
    @AgentDoc(
            value = "Requests transition to the development (CODE) phase.",
            examples = "planService.requestDevelopment('Ready to start coding.')")
    public Object requestDevelopment(String reason) {
        requirePhase("requestDevelopment", WorkflowPhase.PLAN);
        return routeToPhase("CODE", reason);
    }

    /** Transitions the workflow to the review phase. */
    @AgentDoc(
            value = "Requests transition to the review phase.",
            examples = "planService.requestReview('Feature implemented, needs review.')")
    public Object requestReview(String reason) {
        requirePhase("requestReview", WorkflowPhase.CODE);
        return routeToPhase("REVIEW", reason);
    }

    /** Transitions the workflow back to development (CODE) to address fixes. */
    @AgentDoc(
            value = "Requests transition back to development to address fixes.",
            examples = "planService.requestFixes('Found a bug in auth logic.')")
    public Object requestFixes(String reason) {
        requirePhase("requestFixes", WorkflowPhase.REVIEW);
        return routeToPhase("CODE", reason);
    }

    /** Signals completion of the current cycle and returns to planning. */
    @AgentDoc(
            value = "Signals completion of the current cycle and returns to planning.",
            examples = "planService.requestCompletion('All steps verified, project ready.')")
    public Object requestCompletion(String reason) {
        requirePhase("requestCompletion", WorkflowPhase.REVIEW);
        return routeToPhase("PLAN", reason);
    }

    /** Transitions the workflow back to planning (PLAN) to address technical blockers. */
    @AgentDoc(
            value =
                    "Requests transition back to the planning phase (PLAN) due to technical blockers or needed revisions.",
            examples =
                    "planService.requestPlanRevision('Blocked by missing API, need to update the architecture.')")
    public Object requestPlanRevision(String reason) {
        requirePhase("requestPlanRevision", WorkflowPhase.CODE);
        return routeToPhase("PLAN", reason);
    }

    /** Routes the workflow to the target phase. Internal method; not exposed to JEXL. */
    public Object routeToPhase(String phaseName, String reason) {
        WorkflowPhase nextPhase;
        String normalizedName = phaseName.toUpperCase(Locale.ROOT);
        if (PHASE_DEVELOP.equals(normalizedName)) {
            normalizedName = PHASE_CODE;
        }

        try {
            nextPhase = WorkflowPhase.valueOf(normalizedName);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid phase name: "
                    + phaseName
                    + ". Valid phases are: PLAN, CODE, REVIEW.";
        }

        if (nextPhase == currentPhase) {
            return "Already in phase: " + nextPhase;
        }

        if (reason == null || reason.isBlank()) {
            return "Error: You must provide a descriptive 'reason' for requesting a phase transition.";
        }

        // Enforce strict single-step adjacent transitions for task flow
        if (!isAdjacent(currentPhase, nextPhase)) {
            return "Error: Cannot jump from "
                    + currentPhase
                    + " to "
                    + nextPhase
                    + ". Only single-step transitions to adjacent phases are allowed.";
        }

        // Check prerequisites for specific transitions
        if (currentPhase == WorkflowPhase.PLAN && nextPhase == WorkflowPhase.CODE) {
            if (currentPlan == null) {
                return "Error: Cannot advance to CODE. Implementation Plan is missing. Call planService.submitPlan() first.";
            }
        }

        if ((currentPhase == WorkflowPhase.CODE && nextPhase == WorkflowPhase.REVIEW)
                || (currentPhase == WorkflowPhase.REVIEW
                        && (nextPhase == WorkflowPhase.CODE || nextPhase == WorkflowPhase.PLAN))) {
            if (currentPlan == null || !currentPlan.userApproved()) {
                return "Error: Cannot transition from "
                        + currentPhase
                        + " to "
                        + nextPhase
                        + ". An approved Implementation Plan is required.";
            }
        }

        // Autonomous transition logic
        if (isAutonomous(currentPhase, nextPhase)) {
            WorkflowPhase oldPhase = currentPhase;
            String timestamp = OffsetDateTime.now().toString();
            String user = "System";
            String message = String.format("%s -> %s: %s", oldPhase, nextPhase, reason);
            addHandoffLogEntry(timestamp, user, message);

            handleCompletionMetadata(oldPhase, nextPhase, timestamp);

            this.currentPhase = nextPhase;
            this.visitedPhases.add(nextPhase);
            publishPhaseChanged();

            if (eventPublisher != null) {
                eventPublisher.publishEvent(
                        new AutonomousTransitionEvent(oldPhase, nextPhase, reason));
            }
            String autoMessage =
                    String.format(
                            "Autonomous transition from %s to %s successful. Reason: %s",
                            oldPhase, nextPhase, reason);
            return new TransitionResult(autoMessage);
        }
        this.pendingPhase = nextPhase;
        this.pendingReason = reason;
        publishTransitionRequested();

        String summary = formatPlanSummary(currentPlan);
        String message =
                """
                **Transition Request (%s → %s)**

                **Reason:** %s"""
                        .formatted(
                                currentPhase.getDisplayName(), nextPhase.getDisplayName(), reason);
        // Yield execution turn immediately for user approval
        String newlineDouble = String.valueOf((char) 10) + (char) 10;
        String markdown =
                (summary != null && !summary.isBlank())
                        ? summary + newlineDouble + message
                        : message;

        return new TransitionResult(markdown);
    }

    /**
     * Checks whether the given tool execution result represents a structured workflow transition
     * payload that should return directly rather than continuing the LLM tool loop.
     */
    public static boolean isTransitionResponse(Object result) {
        return result instanceof TransitionResult;
    }

    private boolean isAdjacent(WorkflowPhase current, WorkflowPhase next) {
        // EXPLORE is decoupled from linear task flow
        if (next == WorkflowPhase.EXPLORE || current == WorkflowPhase.EXPLORE) {
            return false;
        }

        switch (current) {
            case PLAN:
                return next == WorkflowPhase.CODE;
            case CODE:
                return next == WorkflowPhase.REVIEW || next == WorkflowPhase.PLAN;
            case REVIEW:
                return next == WorkflowPhase.CODE || next == WorkflowPhase.PLAN;
            default:
                return false;
        }
    }

    private boolean isAutonomous(WorkflowPhase from, WorkflowPhase to) {
        if (from == WorkflowPhase.CODE && to == WorkflowPhase.REVIEW) {
            return true;
        }
        if (from == WorkflowPhase.REVIEW && to == WorkflowPhase.CODE) {
            return true;
        }
        return false;
    }

    private void handleCompletionMetadata(WorkflowPhase from, WorkflowPhase to, String timestamp) {
        if (from == WorkflowPhase.REVIEW && to == WorkflowPhase.PLAN) {
            if (currentPlan != null) {
                completePlan(timestamp);
                clearPlan();
            }
        }
    }

    public WorkflowPhase getCurrentPhase() {
        return currentPhase;
    }

    public WorkflowPhase getPendingPhase() {
        return pendingPhase;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void approveTransition() {
        if (pendingPhase != null) {
            String timestamp = OffsetDateTime.now().toString();
            String user = environmentService != null ? environmentService.getCurrentUser() : "User";
            String message =
                    String.format(
                            "%s -> %s (User Approved): %s",
                            currentPhase, pendingPhase, pendingReason);
            addHandoffLogEntry(timestamp, user, message);

            // Plan Approval Metadata
            if (currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE) {
                approvePlan(user, timestamp);
            }

            // Phase Completion Metadata
            handleCompletionMetadata(currentPhase, pendingPhase, timestamp);

            this.currentPhase = pendingPhase;
            this.visitedPhases.add(pendingPhase);
            this.pendingPhase = null;
            this.pendingReason = null;
            publishPhaseChanged();
        }
    }

    public void rejectTransition() {
        this.pendingPhase = null;
        this.pendingReason = null;
        publishTransitionRequested();
    }

    public Set<WorkflowPhase> getVisitedPhases() {
        return Collections.unmodifiableSet(visitedPhases);
    }

    public void resetWorkflow() {
        this.pendingPhase = null;
        this.pendingReason = null;
        this.currentPhase = WorkflowPhase.PLAN;
        this.visitedPhases.clear();
        this.visitedPhases.add(WorkflowPhase.PLAN);
        clearPlan();
        publishPhaseChanged();
        publishTransitionRequested();
    }

    private void publishPhaseChanged() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PhaseChangedEvent(currentPhase));
        }
    }

    private void publishTransitionRequested() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(
                    new TransitionRequestedEvent(currentPhase, pendingPhase, pendingReason));
        }
    }

    private void publishPlanUpdated() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PlanUpdatedEvent(currentPlan));
        }
    }

    private String formatPlanSummary(ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        char nl = 10;
        String wsRegex = "[" + (char) 92 + "s]+";
        StringBuilder sb = new StringBuilder();
        sb.append(
                """
                PLAN SUMMARY:

                ### %s
                **Goal:** %s

                """
                        .formatted(
                                plan.title().trim(), plan.goal().replaceAll(wsRegex, " ").trim()));

        if (!plan.requirements().isEmpty()) {
            sb.append("#### Requirements").append(nl);
            for (String req : plan.requirements()) {
                sb.append("* ").append(req.replaceAll(wsRegex, " ").trim()).append(nl);
            }
            sb.append(nl);
        }

        if (!plan.technicalSteps().isEmpty()) {
            sb.append("#### Technical Steps").append(nl);
            for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
                sb.append(step.completed() ? "* [x] " : "* [ ] ")
                        .append(step.description().replaceAll(wsRegex, " ").trim())
                        .append(nl);
            }
        }
        return sb.toString();
    }

    private void requirePhase(String methodName, WorkflowPhase... allowedPhases) {
        WorkflowPhase current = getCurrentPhase();
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
        if (currentPlan == null) {
            return;
        }
        currentPlan = currentPlan.withAddedHandoffLogEntry(timestamp, user, message);
        publishPlanUpdated();
    }

    /** Adds an entry to the handoff log. */
    @AgentDoc(
            value = "Adds a high-level summary entry to the handoff log.",
            examples = "planService.addHandoffLogEntry('Updated the README.')")
    public void addHandoffLogEntry(String entry) {
        requirePhase(
                "addHandoffLogEntry", WorkflowPhase.PLAN, WorkflowPhase.CODE, WorkflowPhase.REVIEW);
        addHandoffLogEntry(OffsetDateTime.now().toString(), "System", entry);
    }
}
