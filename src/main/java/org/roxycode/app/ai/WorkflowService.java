package org.roxycode.app.ai;

import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.service.EnvironmentService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.text.StringEscapeUtils;

@AgentService(value = "workflowService", phases = {"*"})
@AgentDoc("Manages the current phase of the development workflow and handles single-step phase transitions.")
public class WorkflowService {

    private final PlanManagerService planManager;
    private final EnvironmentService environmentService;
    private WorkflowPhase currentPhase = WorkflowPhase.PLAN;
    private final Set<WorkflowPhase> visitedPhases = new HashSet<>(Collections.singletonList(WorkflowPhase.PLAN));
    private WorkflowPhase pendingPhase;
    private String pendingReason;

    private final List<Consumer<WorkflowPhase>> phaseListeners = new ArrayList<>();
    private final List<TransitionRequestListener> requestListeners = new ArrayList<>();

    public interface TransitionRequestListener {
        void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested, String reason);
    }

    public WorkflowService(PlanManagerService planManager, EnvironmentService environmentService) {
        this.planManager = planManager;
        this.environmentService = environmentService;
        this.planManager.addListener(this::handlePlanUpdate);
    }

    private void handlePlanUpdate(org.roxycode.app.model.ImplementationPlan plan) {
        // Enforce HITL approval for CODE completion. Auto-transition removed.
    }

    /**
     * Handles manual user phase switches from the UI header toggle.
     * Restricted to switching between EXPLORE and PLAN.
     */
    public void switchMode(WorkflowPhase targetPhase) {
        if (targetPhase == currentPhase) {
            if (this.pendingPhase != null) {
                this.pendingPhase = null;
                this.pendingReason = null;
                notifyRequestListeners();
            }
            return;
        }

        if (targetPhase == WorkflowPhase.EXPLORE || targetPhase == WorkflowPhase.PLAN) {
             this.currentPhase = targetPhase;
             this.visitedPhases.add(this.currentPhase);
             this.pendingPhase = null;
             this.pendingReason = null;
             notifyPhaseListeners();
             notifyRequestListeners();
        }
    }

    @AgentDoc("Requests a single-step transition to an adjacent phase with a descriptive reason.")
    public String routeToPhase(
            @AgentDoc("The target phase: EXPLORE, PLAN, or CODE.") String phaseName,
            @AgentDoc("The detailed reason for requesting this transition.") String reason) {

        WorkflowPhase nextPhase;
        String normalizedName = phaseName.toUpperCase();
        if ("DEVELOP".equals(normalizedName)) {
            normalizedName = "CODE";
        }
        
        try {
            nextPhase = WorkflowPhase.valueOf(normalizedName);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid phase name: " + phaseName + ". Valid phases are: EXPLORE, PLAN, CODE.";
        }

        if (nextPhase == currentPhase) {
            return "Already in phase: " + nextPhase;
        }

        if (reason == null || reason.isBlank()) {
            return "Error: You must provide a descriptive 'reason' for requesting a phase transition.";
        }

        // Enforce strict single-step adjacent transitions
        if (!isAdjacent(currentPhase, nextPhase)) {
            return "Error: Cannot jump from " + currentPhase + " to " + nextPhase
                    + ". Only single-step transitions to adjacent phases are allowed.";
        }

        // Check prerequisites for specific transitions
        if (currentPhase == WorkflowPhase.PLAN && nextPhase == WorkflowPhase.CODE) {
            if (planManager.getCurrentPlan() == null) {
                return "Error: Cannot advance to CODE. Implementation Plan is missing. Call planManagerService.submitPlan() first.";
            }
        }

        if (currentPhase == WorkflowPhase.CODE && (nextPhase == WorkflowPhase.PLAN || nextPhase == WorkflowPhase.EXPLORE)) {
            org.roxycode.app.model.ImplementationPlan plan = planManager.getCurrentPlan();
            if (plan != null) {
                boolean allDone = plan.technicalSteps().stream().allMatch(org.roxycode.app.model.ImplementationPlan.TechStep::completed);
                if (!allDone) {
                    String lowReason = reason.toLowerCase();
                    boolean isBlocker = lowReason.contains("block") || lowReason.contains("revise") || lowReason.contains("issue") || lowReason.contains("fix") || lowReason.contains("problem");
                    if (!isBlocker) {
                        return "Error: Cannot complete phase. Some technical steps are incomplete. If you are blocked, please clarify in the reason.";
                    }
                }
            }
        }

        // Set pending state and notify listeners
        this.pendingPhase = nextPhase;
        this.pendingReason = reason;
        notifyRequestListeners();

        String summary = formatPlanSummary(planManager.getCurrentPlan());
        String message = "**Transition Request (" + currentPhase.getDisplayName() + " → " + nextPhase.getDisplayName() + ")**\\n\\n"
                + "**Reason:** " + reason;

        // Yield execution turn immediately for user approval
        String json = String.format("{\\\"summary\\\": \\\"%s\\\", \\\"message\\\": \\\"%s\\\", \\\"reason\\\": \\\"%s\\\"}",
                StringEscapeUtils.escapeJson(summary),
                StringEscapeUtils.escapeJson(message),
                StringEscapeUtils.escapeJson(reason));

        throw new YieldTurnException(json);
    }

    private boolean isAdjacent(WorkflowPhase current, WorkflowPhase next) {
        switch (current) {
            case EXPLORE:
                return next == WorkflowPhase.PLAN;
            case PLAN:
                return next == WorkflowPhase.EXPLORE || next == WorkflowPhase.CODE;
            case CODE:
                return next == WorkflowPhase.PLAN || next == WorkflowPhase.EXPLORE;
            default:
                return false;
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
            String timestamp = java.time.OffsetDateTime.now().toString();
            String user = environmentService.getCurrentUser();

            // Plan Approval Metadata
            if (currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE) {
                planManager.approvePlan(user, timestamp);
            }
            
            // Phase Completion Metadata
            if (currentPhase == WorkflowPhase.CODE && pendingPhase == WorkflowPhase.PLAN) {
                org.roxycode.app.model.ImplementationPlan plan = planManager.getCurrentPlan();
                if (plan != null) {
                    boolean allDone = plan.technicalSteps().stream().allMatch(org.roxycode.app.model.ImplementationPlan.TechStep::completed);
                    if (allDone && !plan.technicalSteps().isEmpty()) {
                        planManager.completePlan(timestamp);
                        planManager.clearPlan();
                    }
                }
            }

            this.currentPhase = pendingPhase;
            this.visitedPhases.add(pendingPhase);
            this.pendingPhase = null;
            this.pendingReason = null;
            notifyPhaseListeners();
        }
    }

    public void rejectTransition() {
        this.pendingPhase = null;
        this.pendingReason = null;
        notifyRequestListeners();
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
        this.planManager.clearPlan();
        notifyPhaseListeners();
        notifyRequestListeners();
    }

    public void addPhaseListener(Consumer<WorkflowPhase> listener) {
        phaseListeners.add(listener);
        listener.accept(currentPhase);
    }

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
            listener.onTransitionRequested(currentPhase, pendingPhase, pendingReason);
        }
    }

    private String formatPlanSummary(org.roxycode.app.model.ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("PLAN SUMMARY:\\\\n\\\\n### ").append(plan.title().trim()).append("\\\\n");
        sb.append("**Goal:** ").append(plan.goal().replaceAll("\\\\s+", " ").trim()).append("\\\\n\\\\n");

        if (!plan.requirements().isEmpty()) {
            sb.append("#### Requirements\\\\n");
            for (String req : plan.requirements()) {
                sb.append("* ").append(req.replaceAll("\\\\s+", " ").trim()).append("\\\\n");
            }
            sb.append("\\\\n");
        }

        if (!plan.technicalSteps().isEmpty()) {
            sb.append("#### Technical Steps\\\\n");
            for (org.roxycode.app.model.ImplementationPlan.TechStep step : plan.technicalSteps()) {
                sb.append(step.completed() ? "* [x] " : "* [ ] ").append(step.description().replaceAll("\\\\s+", " ").trim()).append("\\\\n");
            }
        }
        return sb.toString();
    }
}
