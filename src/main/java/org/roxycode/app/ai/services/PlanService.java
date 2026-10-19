package org.roxycode.app.ai.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.model.ImplementationPlan;
import org.springframework.context.annotation.Lazy;

/** Service for managing project plans and specifications. */
@AgentService(
    value = "planService",
    phases = {"PLAN", "CODE", "REVIEW"})
@AgentDoc("Service for managing project plans and specifications.")

public class PlanService {

  private ImplementationPlan currentPlan;
  private final WorkflowService workflowService;
  private final List<PlanUpdateListener> listeners = new CopyOnWriteArrayList<>();

  /** Listener for plan updates. */
  @FunctionalInterface
  public interface PlanUpdateListener {
    void onPlanUpdated(ImplementationPlan plan);
  }

  public PlanService(@Lazy WorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  /**
   * Adds a listener for plan updates.
   *
   * @param listener the listener to add
   */
  public void addListener(PlanUpdateListener listener) {
    listeners.add(listener);
  }

  /**
   * Submits an implementation plan for the project.
   *
   * @param plan the implementation plan to submit
   * @return a success message
   */
  public String submitPlan(
      @AgentDoc("The implementation plan to submit.") ImplementationPlan plan) {
    if (plan == null) {
      return "Error: Plan cannot be null.";
    }

    List<ImplementationPlan.TechStep> steps = new ArrayList<>(plan.technicalSteps());

    // 1. Auto-append mandatory compile step
    boolean hasCompile =
        steps.stream().anyMatch(s -> s.description().toLowerCase(Locale.ROOT).contains("compile"));
    if (!hasCompile) {
      steps.add(new ImplementationPlan.TechStep("Code compiles successfully", false));
    }

    // 2. Auto-append mandatory test step
    boolean hasTests =
        steps.stream()
            .anyMatch(
                s ->
                    s.description().toLowerCase(Locale.ROOT).contains("unit test")
                        || s.description().toLowerCase(Locale.ROOT).contains("tests pass"));
    if (!hasTests) {
      steps.add(new ImplementationPlan.TechStep("Unit tests pass", false));
    }

    // 3. Auto-append mandatory code review step
    boolean hasReview =
        steps.stream()
            .anyMatch(s -> s.description().toLowerCase(Locale.ROOT).contains("code review"));
    if (!hasReview) {
      steps.add(new ImplementationPlan.TechStep("Automated code review passes", false));
    }

        this.currentPlan =
        new ImplementationPlan(
            plan.title(),
            plan.goal(),
            plan.requirements(),
            steps,
            plan.userApproved(),
            plan.approvalTimestamp(),
            plan.approvedBy(),
            plan.completedOn(),
            plan.handoffLog());
    notifyListeners();
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
      @AgentDoc("List of functional requirements (String array or list).") Object requirements,
      @AgentDoc("List of technical implementation steps (String array or list).")
          Object technicalSteps) {
    List<String> rawRequirements = extractStringList(requirements);
    List<String> rawTechSteps = extractStringList(technicalSteps);

    List<ImplementationPlan.TechStep> steps =
        rawTechSteps.stream()
            .map(s -> new ImplementationPlan.TechStep(s, false))
            .collect(Collectors.toList());

        return submitPlan(
        new ImplementationPlan(
            title, goal, rawRequirements, steps, false, null, null, null, null));
  }

  /**
   * Submits a project plan covering both functional and technical specifications via a Map literal.
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
    String title = (String) planMap.getOrDefault("title", "");
    String goal = (String) planMap.getOrDefault("goal", "");
    List<String> requirements = extractStringList(planMap.get("requirements"));
    List<String> rawTechSteps = extractStringList(planMap.get("technicalSteps"));

    List<ImplementationPlan.TechStep> steps =
        rawTechSteps.stream()
            .map(s -> new ImplementationPlan.TechStep(s, false))
            .collect(Collectors.toList());

        return submitPlan(
        new ImplementationPlan(
            title, goal, requirements, steps, false, null, null, null, null));
  }

  /** Approves the current plan. */
  @AgentDoc("Approves the current implementation plan.")
  public void approvePlan(String user, String timestamp) {
    if (currentPlan == null) return;
        currentPlan =
        new ImplementationPlan(
            currentPlan.title(),
            currentPlan.goal(),
            currentPlan.requirements(),
            currentPlan.technicalSteps(),
            true,
            timestamp,
            user,
            currentPlan.completedOn(),
            currentPlan.handoffLog());
    notifyListeners();
  }

  /** Marks the current plan as completed. */
  @AgentDoc("Marks the current implementation plan as completed.")
  public void completePlan(String timestamp) {
    if (currentPlan == null) return;
        currentPlan =
        new ImplementationPlan(
            currentPlan.title(),
            currentPlan.goal(),
            currentPlan.requirements(),
            currentPlan.technicalSteps(),
            currentPlan.userApproved(),
            currentPlan.approvalTimestamp(),
            currentPlan.approvedBy(),
            timestamp,
            currentPlan.handoffLog());
    notifyListeners();
  }

  /** Marks a specific technical implementation step as completed using its 0-based index. */
  @AgentDoc(
      value =
          "Marks a specific technical implementation step as completed using its 0-based index.",
      examples = "planService.markStepCompleted(0)")
  public void markStepCompleted(
      @AgentDoc("The index of the step (0-based). index must be valid for the current plan.")
          int index) {
    updateStepStatus(index, true);
  }

  /** Marks a technical implementation step as incomplete. */
  @AgentDoc(
      value = "Marks a technical implementation step as incomplete.",
      examples = "planService.markStepIncomplete(0)")
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
        currentPlan =
        new ImplementationPlan(
            currentPlan.title(),
            currentPlan.goal(),
            currentPlan.requirements(),
            steps,
            currentPlan.userApproved(),
            currentPlan.approvalTimestamp(),
            currentPlan.approvedBy(),
            currentPlan.completedOn(),
            currentPlan.handoffLog());
    notifyListeners();
  }

  /** Gets the current implementation plan. */
  public ImplementationPlan getCurrentPlan() {
    return currentPlan;
  }

  /** Clears the existing plan. */
  @SuppressWarnings("PMD.NullAssignment")
  @AgentDoc("Clears the current implementation plan.")
  public void clearPlan() {
    this.currentPlan = null;
    notifyListeners();
  }

  public void clearSpecs() {
    clearPlan();
  }

  /** Transitions the workflow to the development (CODE) phase. */
  @AgentDoc(
      value = "Requests transition to the development (CODE) phase.",
      examples = "planService.requestDevelopment('Ready to start coding.')")
  public String requestDevelopment(String reason) {
    return workflowService.routeToPhase("CODE", reason);
  }

  /** Transitions the workflow to the review phase. */
  @AgentDoc(
      value = "Requests transition to the review phase.",
      examples = "planService.requestReview('Feature implemented, needs review.')")
  public String requestReview(String reason) {
    return workflowService.routeToPhase("REVIEW", reason);
  }

  /** Transitions the workflow back to development (CODE) to address fixes. */
  @AgentDoc(
      value = "Requests transition back to development to address fixes.",
      examples = "planService.requestFixes('Found a bug in auth logic.')")
  public String requestFixes(String reason) {
    return workflowService.routeToPhase("CODE", reason);
  }

  /** Signals completion of the current cycle and returns to planning. */
  @AgentDoc(
      value = "Signals completion of the current cycle and returns to planning.",
      examples = "planService.requestCompletion('All steps verified, project ready.')")
  public String requestCompletion(String reason) {
    return workflowService.routeToPhase("PLAN", reason);
  }

  /** Transitions the workflow back to planning (PLAN) to address technical blockers. */
  @AgentDoc(
      value =
          "Requests transition back to the planning phase (PLAN) due to technical blockers or needed revisions.",
      examples =
          "planService.requestPlanRevision('Blocked by missing API, need to update the architecture.')")
  public String requestPlanRevision(String reason) {
    return workflowService.routeToPhase("PLAN", reason);
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

  

  /** Adds an entry to the handoff log. */
  public void addHandoffLogEntry(String timestamp, String user, String message) {
    if (currentPlan == null) return;
    List<ImplementationPlan.LogEntry> logs = new ArrayList<>(currentPlan.handoffLog());
    logs.add(new ImplementationPlan.LogEntry(timestamp, user, message));
        currentPlan =
        new ImplementationPlan(
            currentPlan.title(),
            currentPlan.goal(),
            currentPlan.requirements(),
            currentPlan.technicalSteps(),
            currentPlan.userApproved(),
            currentPlan.approvalTimestamp(),
            currentPlan.approvedBy(),
            currentPlan.completedOn(),
            logs);
    notifyListeners();
  }

  /** Adds an entry to the handoff log. */
  @AgentDoc(
      value = "Adds a high-level summary entry to the handoff log.",
      examples = "planService.addHandoffLogEntry('Updated the README.')")
  public void addHandoffLogEntry(String entry) {
    addHandoffLogEntry(java.time.OffsetDateTime.now().toString(), "System", entry);
  }
}