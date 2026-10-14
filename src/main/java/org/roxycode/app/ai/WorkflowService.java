package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.commons.text.StringEscapeUtils;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class WorkflowService {
  private static final String PHASE_DEVELOP = "DEVELOP";
  private static final String PHASE_CODE = "CODE";

  private final PlanService planManager;
  private final EnvironmentService environmentService;
  private final ApplicationEventPublisher eventPublisher;
  private WorkflowPhase currentPhase = WorkflowPhase.PLAN;
  private final Set<WorkflowPhase> visitedPhases = EnumSet.of(WorkflowPhase.PLAN);
  private WorkflowPhase pendingPhase;
  private String pendingReason;

  private final List<Consumer<WorkflowPhase>> phaseListeners = new ArrayList<>();
  private final List<TransitionRequestListener> requestListeners = new ArrayList<>();

  @FunctionalInterface
  public interface TransitionRequestListener {
    void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested, String reason);
  }

  public WorkflowService(
      @Lazy PlanService planManager,
      EnvironmentService environmentService,
      ApplicationEventPublisher eventPublisher) {
    this.planManager = planManager;
    this.environmentService = environmentService;
    this.eventPublisher = eventPublisher;
    this.planManager.addListener(plan -> handlePlanUpdate());
  }

  private void handlePlanUpdate() {
    // Enforce HITL approval for CODE completion. Auto-transition removed.
  }

  /**
   * Handles manual user phase switches from the UI header toggle. EXPLORE is decoupled and can be
   * entered/exited freely. PLAN is the gateway for task workflows.
   * @param targetPhase Phase moving to
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

    if (targetPhase == WorkflowPhase.PLAN) {
      this.currentPhase = targetPhase;
      this.visitedPhases.add(this.currentPhase);
      this.pendingPhase = null;
      this.pendingReason = null;
      notifyPhaseListeners();
      notifyRequestListeners();
    }
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
      if (planManager.getCurrentPlan() == null) {
        return "Error: Cannot advance to CODE. Implementation Plan is missing. Call planService.submitPlan() first.";
      }
    }

    // Autonomous transition logic
    if (isAutonomous(currentPhase, nextPhase)) {
      WorkflowPhase oldPhase = currentPhase;
      String timestamp = java.time.OffsetDateTime.now().toString();
      String user = "System";
      String message = String.format("%s -> %s: %s", oldPhase, nextPhase, reason);
      planManager.addHandoffLogEntry(timestamp, user, message);
      if ((oldPhase == WorkflowPhase.CODE && nextPhase == WorkflowPhase.REVIEW)
          || (oldPhase == WorkflowPhase.REVIEW && nextPhase == WorkflowPhase.CODE)) {
        planManager.addCodeReviewLogEntry(timestamp, user, message);
      }
      handleCompletionMetadata(oldPhase, nextPhase, timestamp);

      this.currentPhase = nextPhase;
      this.visitedPhases.add(nextPhase);
      notifyPhaseListeners();

                  eventPublisher.publishEvent(new AutonomousTransitionEvent(oldPhase, nextPhase, reason));

      String autoMessage =
          String.format(
              "Autonomous transition from %s to %s successful. Reason: %s",
              oldPhase, nextPhase, reason);
      String autoJson =
          String.format(
              "{\"message\": \"%s\", \"reason\": \"%s\"}",
              StringEscapeUtils.escapeJson(autoMessage),
              StringEscapeUtils.escapeJson(reason));
      throw new YieldTurnException(autoJson);
    }
    this.pendingPhase = nextPhase;
    this.pendingReason = reason;
    notifyRequestListeners();

    String summary = formatPlanSummary(planManager.getCurrentPlan());
    String message =
        "**Transition Request ("
            + currentPhase.getDisplayName()
            + " → "
            + nextPhase.getDisplayName()
            + ")**\n\n"
            + "**Reason:** "
            + reason;

    // Yield execution turn immediately for user approval
    String json =
        String.format(
            "{\"summary\": \"%s\", \"message\": \"%s\", \"reason\": \"%s\"}",
            StringEscapeUtils.escapeJson(summary),
            StringEscapeUtils.escapeJson(message),
            StringEscapeUtils.escapeJson(reason));

    throw new YieldTurnException(json);
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
    if (from == WorkflowPhase.CODE && to == WorkflowPhase.PLAN) {
      return true;
    }
    return false;
  }

  private void handleCompletionMetadata(WorkflowPhase from, WorkflowPhase to, String timestamp) {
    if ((from == WorkflowPhase.CODE || from == WorkflowPhase.REVIEW) && to == WorkflowPhase.PLAN) {
      ImplementationPlan plan = planManager.getCurrentPlan();
      if (plan != null) {
        boolean allDone =
            plan.technicalSteps().stream()
                .allMatch(ImplementationPlan.TechStep::completed);
        if (allDone && !plan.technicalSteps().isEmpty()) {
          planManager.completePlan(timestamp);
          planManager.clearPlan();
        }
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
          String.format("%s -> %s (User Approved): %s", currentPhase, pendingPhase, pendingReason);
      planManager.addHandoffLogEntry(timestamp, user, message);

      // Plan Approval Metadata
      if (currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE) {
        planManager.approvePlan(user, timestamp);
      }

      // Phase Completion Metadata
      handleCompletionMetadata(currentPhase, pendingPhase, timestamp);

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
    sb.append("PLAN SUMMARY:\n\n### ").append(plan.title().trim()).append("\n");
    sb.append("**Goal:** ").append(plan.goal().replaceAll("\\s+", " ").trim()).append("\n\n");

    if (!plan.requirements().isEmpty()) {
      sb.append("#### Requirements\n");
      for (String req : plan.requirements()) {
        sb.append("* ").append(req.replaceAll("\\s+", " ").trim()).append("\n");
      }
      sb.append("\n");
    }

    if (!plan.technicalSteps().isEmpty()) {
      sb.append("#### Technical Steps\n");
      for (org.roxycode.app.model.ImplementationPlan.TechStep step : plan.technicalSteps()) {
        sb.append(step.completed() ? "* [x] " : "* [ ] ")
            .append(step.description().replaceAll("\\s+", " ").trim())
            .append("\n");
      }
    }
    return sb.toString();
  }
}
