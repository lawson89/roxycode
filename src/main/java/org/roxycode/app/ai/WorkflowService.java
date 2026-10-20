package org.roxycode.app.ai;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.text.StringEscapeUtils;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.TransitionRequestedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class WorkflowService {
    private static final String PHASE_DEVELOP = "DEVELOP";
    private static final String PHASE_CODE = "CODE";

    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private ImplementationPlan currentPlan;
    private WorkflowPhase currentPhase = WorkflowPhase.PLAN;
    private final Set<WorkflowPhase> visitedPhases = EnumSet.of(WorkflowPhase.PLAN);
    private WorkflowPhase pendingPhase;
    private String pendingReason;

    public WorkflowService(
            EnvironmentService environmentService, ApplicationEventPublisher eventPublisher) {
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
    }

    public ImplementationPlan getCurrentPlan() {
        return currentPlan;
    }

    public void setCurrentPlan(ImplementationPlan plan) {
        this.currentPlan = plan;
        publishPlanUpdated();
    }

    public void approvePlan(String user, String timestamp) {
        if (currentPlan == null) {
            return;
        }
        currentPlan = currentPlan.withApproval(user, timestamp);
        publishPlanUpdated();
    }

    public void completePlan(String timestamp) {
        if (currentPlan == null) {
            return;
        }
        currentPlan = currentPlan.withCompletedOn(timestamp);
        publishPlanUpdated();
    }

    public void clearPlan() {
        this.currentPlan = null;
        publishPlanUpdated();
    }

    public void addHandoffLogEntry(String timestamp, String user, String message) {
        if (currentPlan == null) {
            return;
        }
        currentPlan = currentPlan.withAddedHandoffLogEntry(timestamp, user, message);
        publishPlanUpdated();
    }

    public String routeToPhase(String phaseName, String reason) {

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

        // Autonomous transition logic
        if (isAutonomous(currentPhase, nextPhase)) {
            WorkflowPhase oldPhase = currentPhase;
            String timestamp = java.time.OffsetDateTime.now().toString();
            String user = "System";
            String message = String.format("%s -> %s: %s", oldPhase, nextPhase, reason);
            addHandoffLogEntry(timestamp, user, message);

            handleCompletionMetadata(oldPhase, nextPhase, timestamp);

            this.currentPhase = nextPhase;
            this.visitedPhases.add(nextPhase);
            publishPhaseChanged();

            eventPublisher.publishEvent(new AutonomousTransitionEvent(oldPhase, nextPhase, reason));
                        String autoMessage =
                    String.format(
                            "Autonomous transition from %s to %s successful. Reason: %s",
                            oldPhase, nextPhase, reason);
            String autoJson =
                    """
                    {"message": "%s", "reason": "%s"}"""
                            .formatted(
                                    StringEscapeUtils.escapeJson(autoMessage),
                                    StringEscapeUtils.escapeJson(reason));
            return autoJson;
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
        String json =
                """
                {"summary": "%s", "message": "%s", "reason": "%s"}"""
                        .formatted(
                                StringEscapeUtils.escapeJson(summary),
                                StringEscapeUtils.escapeJson(message),
                                StringEscapeUtils.escapeJson(reason));

        return json;
    }

    /**
     * Checks whether the given tool execution result represents a structured workflow transition
     * payload that should return directly rather than continuing the LLM tool loop.
     */
    public static boolean isTransitionResponse(Object result) {
        if (!(result instanceof String str)) {
            return false;
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty() || !trimmed.contains("message")) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '"') {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    AiConfig.SHARED_OBJECT_MAPPER.readTree(trimmed);
            if (node.isTextual()) {
                node = AiConfig.SHARED_OBJECT_MAPPER.readTree(node.asText());
            }
            return node.isObject() && node.has("message");
        } catch (Exception e) {
            return false;
        }
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
            String timestamp = java.time.OffsetDateTime.now().toString();
            String user = environmentService.getCurrentUser();
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
        eventPublisher.publishEvent(new PhaseChangedEvent(currentPhase));
    }

    private void publishTransitionRequested() {
        eventPublisher.publishEvent(
                new TransitionRequestedEvent(currentPhase, pendingPhase, pendingReason));
    }

    private void publishPlanUpdated() {
        eventPublisher.publishEvent(new PlanUpdatedEvent(currentPlan));
    }

            private String formatPlanSummary(org.roxycode.app.model.ImplementationPlan plan) {
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
            for (org.roxycode.app.model.ImplementationPlan.TechStep step : plan.technicalSteps()) {
                sb.append(step.completed() ? "* [x] " : "* [ ] ")
                        .append(step.description().replaceAll(wsRegex, " ").trim())
                        .append(nl);
            }
        }
                return sb.toString();
    }
}
