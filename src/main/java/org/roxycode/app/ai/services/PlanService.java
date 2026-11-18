package org.roxycode.app.ai.services;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;

@AgentService(value = "planService", phases = {"PLAN", "CODE", "REVIEW"})
@AgentDoc("Manages implementation plans, technical step progress, handoff logs, and workflow phase transitions.")
@SuppressWarnings({"PMD.NullAssignment", "PMD.AvoidDuplicateLiterals"})
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    static final String ARCHIVE_PLANS_SUBDIR = "archive/plans";
    static final String ACTIVE_WORKFLOW_FILENAME = "active-workflow.json";
    static final int MAX_PLAN_SLUG_LENGTH = 40;
    public static final String TASK_CONVERSATION_ID = "task:default";

    public record ActiveWorkflowState(ImplementationPlan currentPlan, WorkflowPhase currentPhase,
            WorkflowPhase pendingPhase, String pendingReason, List<WorkflowPhase> visitedPhases,
            ImplementationPlan.UsageSummary unassignedTaskUsage) {
    }

    public static final String APPROVAL_PLAN_TO_CODE = "Approve Plan and proceed to Code";
    public static final String APPROVAL_REVIEW_TO_PLAN = "Approve Feature Request as complete";
    public static final String APPROVAL_CODE_TO_PLAN = "Approve Plan Revision and return to Plan";

    private record TransitionKey(WorkflowPhase from, WorkflowPhase to) {
    }

    private record TransitionRule(boolean autonomous, boolean requiresApprovedPlan) {
    }

    public static String formatCommitMessage(ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        if (StringUtils.isNotBlank(plan.commitMessage())) {
            return plan.commitMessage().trim();
        }
        StringBuilder sb = new StringBuilder();
        String title = StringUtils.trimToEmpty(plan.title());
        if (!title.isEmpty()) {
            sb.append(title);
        }
        String goal = StringUtils.trimToEmpty(plan.goal());
        if (!goal.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(goal);
        }
        List<String> completedSteps = new ArrayList<>();
        for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
            if (step != null && step.completed() && StringUtils.isNotBlank(step.description())
                    && !ImplementationPlan.isMandatoryVerificationStep(step.description())) {
                completedSteps.add(step.description().trim());
            }
        }
        if (!completedSteps.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            for (int i = 0; i < completedSteps.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append("- ").append(completedSteps.get(i));
            }
        }
        return sb.toString();
    }

    public static String formatApprovalLabel(WorkflowPhase from, WorkflowPhase to) {
        if (from == WorkflowPhase.PLAN && to == WorkflowPhase.CODE) {
            return APPROVAL_PLAN_TO_CODE;
        }
        if (from == WorkflowPhase.REVIEW && to == WorkflowPhase.PLAN) {
            return APPROVAL_REVIEW_TO_PLAN;
        }
        if (from == WorkflowPhase.CODE && to == WorkflowPhase.PLAN) {
            return APPROVAL_CODE_TO_PLAN;
        }
        String fromName = "";
        if (from != null) {
            fromName = from.getDisplayName();
        }
        String toName = "";
        if (to != null) {
            toName = to.getDisplayName();
        }
        return fromName + " → " + toName;
    }

    private static final Map<TransitionKey, TransitionRule> TRANSITION_RULES = Map.of(
            new TransitionKey(WorkflowPhase.PLAN, WorkflowPhase.CODE), new TransitionRule(false, false),
            new TransitionKey(WorkflowPhase.CODE, WorkflowPhase.REVIEW), new TransitionRule(true, true),
            new TransitionKey(WorkflowPhase.CODE, WorkflowPhase.PLAN), new TransitionRule(false, false),
            new TransitionKey(WorkflowPhase.REVIEW, WorkflowPhase.CODE), new TransitionRule(true, true),
            new TransitionKey(WorkflowPhase.REVIEW, WorkflowPhase.PLAN), new TransitionRule(false, true));

    private static final Map<String, List<WorkflowPhase>> ALLOWED_PHASES_BY_METHOD = buildAllowedPhasesByMethod();

    private static Map<String, List<WorkflowPhase>> buildAllowedPhasesByMethod() {
        Map<String, List<WorkflowPhase>> map = new LinkedHashMap<>();
        for (Method method : PlanService.class.getDeclaredMethods()) {
            AgentDoc doc = AnnotationUtils.findAnnotation(method, AgentDoc.class);
            if (doc == null) {
                continue;
            }
            List<WorkflowPhase> phases = new ArrayList<>();
            for (WorkflowPhase candidate : WorkflowPhase.values()) {
                if (WorkflowPhase.isAllowed(doc.phases(), candidate)) {
                    phases.add(candidate);
                }
            }
            map.putIfAbsent(method.getName(), List.copyOf(phases));
        }
        return Collections.unmodifiableMap(map);
    }

    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProjectService projectService;
    private record PersistSnapshot(Path path, @Nullable ActiveWorkflowState state, boolean deleteOnly) {
    }

    private final Object stateLock = new Object();
    private final Object persistLock = new Object();
    private CompletableFuture<Void> lastPersistFuture = CompletableFuture.completedFuture(null);
    private volatile ImplementationPlan currentPlan;
    private ImplementationPlan.UsageSummary unassignedTaskUsage = ImplementationPlan.UsageSummary.EMPTY;
    private volatile WorkflowPhase currentPhase = WorkflowPhase.PLAN;
    private final Set<WorkflowPhase> visitedPhases = EnumSet.of(WorkflowPhase.PLAN);
    private volatile WorkflowPhase pendingPhase;
    private volatile String pendingReason;

    public PlanService(EnvironmentService environmentService, ApplicationEventPublisher eventPublisher,
            ProjectService projectService) {
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
        this.projectService = projectService;
        restoreWorkflowForCurrentProject(false);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        restoreWorkflowForCurrentProject(true);
    }

    @EventListener
    public void onFileChanged(FileChangedEvent event) {
        if (event == null) {
            return;
        }
        ImplementationPlan updated;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            if (currentPlan == null) {
                return;
            }
            updated = currentPlan;
            if (StringUtils.isNotBlank(event.path())) {
                updated = updated.withRecordedFileChange(event.path(), event.changeType());
            }
            if (currentPhase == WorkflowPhase.CODE) {
                updated = updated.withVerificationStepCompleted(ImplementationPlan.STEP_COMPILE, false)
                        .withVerificationStepCompleted(ImplementationPlan.STEP_UNIT_TESTS, false);
            }
            this.currentPlan = updated;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updated);
    }

    @EventListener
    public void onTokenUsage(TokenUsageEvent event) {
        if (event == null || !TASK_CONVERSATION_ID.equals(event.conversationId())) {
            return;
        }
        ImplementationPlan updatedPlan = null;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            if (currentPlan != null) {
                updatedPlan = currentPlan.withRecordedTokenUsage(event);
                this.currentPlan = updatedPlan;
            } else {
                unassignedTaskUsage = unassignedTaskUsage.addEvent(event);
            }
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        if (updatedPlan != null) {
            publishPlanUpdated(updatedPlan);
        }
    }

    /**
     * Submits a new implementation plan and transitions to the planning approval state.
     * 
     * @param plan
     *            the implementation plan to submit
     * @return a status message
     */
    public String submitPlan(ImplementationPlan plan) {
        requirePhase("submitPlan");
        if (plan == null) {
            throw new IllegalArgumentException("Error: Plan cannot be null.");
        }
        ImplementationPlan normalized;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            requirePhase("submitPlan");
            normalized = plan.withNormalizedStepsFrom(currentPlan);
            if (unassignedTaskUsage.hasUsage()) {
                normalized = normalized.withUsageSummary(normalized.usageSummary().merge(unassignedTaskUsage));
                unassignedTaskUsage = ImplementationPlan.UsageSummary.EMPTY;
            }
            this.currentPlan = normalized;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(normalized);
        return "Implementation plan submitted successfully with mandatory verification checklist items.";
    }

    @SuppressWarnings("PMD.UseVarargs")
    @AgentDoc(value = "Submits a project plan covering both functional requirements and technical implementation steps.", phases = {
            "PLAN"}, examples = {"planService.submitPlan('Add Feature', 'Goal description', ['Req 1'], ['Step 1'])"})
    public String submitPlan(
            @AgentDoc("The concise title of the project or feature plan (max 50 characters)") String title,
            @AgentDoc("The high-level goal of the plan") String goal,
            @AgentDoc("Array of functional requirements") String[] requirements,
            @AgentDoc("Array of technical implementation steps") String[] technicalSteps) {
        requirePhase("submitPlan");
        List<String> safeRequirements = Arrays.asList(ArrayUtils.nullToEmpty(requirements));
        List<ImplementationPlan.TechStep> steps = Arrays.stream(ArrayUtils.nullToEmpty(technicalSteps))
                .map(s -> new ImplementationPlan.TechStep(s, false)).toList();

        return submitPlan(new ImplementationPlan(StringUtils.defaultString(title), StringUtils.defaultString(goal),
                safeRequirements, steps, false, null, null, null, null, null));
    }

    private static String requireNonBlankDescription(String description) {
        if (StringUtils.isBlank(description)) {
            throw new IllegalArgumentException("Error: Step description cannot be null or blank.");
        }
        return description.trim();
    }

    @AgentDoc(value = "Adds a new technical implementation step to the current plan (inserted before trailing verification gates) without losing progress on completed steps.", phases = {
            "PLAN", "CODE", "REVIEW"}, examples = {"planService.addTechnicalStep('Add unit tests for edge cases')"})
    public String addTechnicalStep(
            @AgentDoc("Description of the new technical implementation step") String description) {
        requirePhase("addTechnicalStep");
        String trimmed = requireNonBlankDescription(description);
        int insertIndex;
        ImplementationPlan updated;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            requirePhase("addTechnicalStep");
            ensureActivePlanLocked();
            List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
            insertIndex = insertStepBeforeVerificationGates(steps, trimmed);
            updated = currentPlan.withTechnicalSteps(steps);
            this.currentPlan = updated;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updated);
        return String.format("Added technical step at index %d: '%s'. %s", insertIndex, trimmed,
                updated.formatRemainingIncompleteSteps());
    }

    @AgentDoc(value = "Updates the description of an existing technical step at the given 0-based index while preserving its completion status.", phases = {
            "PLAN", "CODE", "REVIEW"}, examples = {"planService.updateTechnicalStep(0, 'Updated step description')"})
    public String updateTechnicalStep(@AgentDoc("The 0-based index of the step to update") int index,
            @AgentDoc("The updated description for the technical step") String description) {
        requirePhase("updateTechnicalStep");
        String trimmed = requireNonBlankDescription(description);
        ImplementationPlan updated;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            requirePhase("updateTechnicalStep");
            ensureActivePlanLocked();
            List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
            validateStepIndex(index, steps.size());
            steps.set(index, new ImplementationPlan.TechStep(trimmed, steps.get(index).completed()));
            updated = currentPlan.withTechnicalSteps(steps);
            this.currentPlan = updated;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updated);
        return String.format("Updated technical step %d to '%s'. %s", index, trimmed,
                updated.formatRemainingIncompleteSteps());
    }

    void updateVerificationStep(String stepDescription, boolean completed) {
        ImplementationPlan updated;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            if (currentPlan == null) {
                return;
            }
            updated = currentPlan.withVerificationStepCompleted(stepDescription, completed);
            if (updated.equals(currentPlan)) {
                return;
            }
            this.currentPlan = updated;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updated);
    }

    @AgentDoc(value = "Marks a specific technical implementation step (0..N) as completed using its 0-based index. Call this in the same executeJexl turn immediately after applying the file edits for that step (build and test verification steps are marked automatically by buildToolService).", phases = {
            "CODE", "REVIEW"}, examples = {"planService.markStepCompleted(0)"})
    public String markStepCompleted(
            @AgentDoc("The index of the step (0-based). Must be valid for the current plan") int index) {
        requirePhase("markStepCompleted");
        return updateStepsStatus(new int[]{index}, true);
    }

    @AgentDoc(value = "Marks multiple technical implementation steps (0..N) as completed in a single batch call (0-based indices). Call this in the same executeJexl turn immediately after applying the file edits for those steps (build and test verification steps are marked automatically by buildToolService).", phases = {
            "CODE", "REVIEW"}, examples = {"planService.markStepsCompleted([0, 1])"})
    public String markStepsCompleted(@AgentDoc("Array of 0-based step indices to mark as completed") int... indices) {
        requirePhase("markStepsCompleted");
        return updateStepsStatus(indices, true);
    }

    @AgentDoc(value = "Marks a technical implementation step as incomplete using its 0-based index.", phases = {"CODE",
            "REVIEW"}, examples = {"planService.markStepIncomplete(0)"})
    public String markStepIncomplete(@AgentDoc("The index of the step (0-based)") int index) {
        requirePhase("markStepIncomplete");
        return updateStepsStatus(new int[]{index}, false);
    }

    @AgentDoc(value = "Marks multiple technical implementation steps as incomplete in a single batch call (0-based indices).", phases = {
            "CODE", "REVIEW"}, examples = {"planService.markStepsIncomplete([0, 1])"})
    public String markStepsIncomplete(@AgentDoc("Array of 0-based step indices to mark as incomplete") int... indices) {
        requirePhase("markStepsIncomplete");
        return updateStepsStatus(indices, false);
    }

    private String updateStepsStatus(int[] indices, boolean completed) {
        if (indices == null || indices.length == 0) {
            throw new IllegalArgumentException("Error: Step indices cannot be null or empty.");
        }
        ImplementationPlan updated;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            ensureActivePlanLocked();
            List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
            for (int index : indices) {
                validateStepIndex(index, steps.size());
                if (completed && currentPhase == WorkflowPhase.CODE) {
                    validateManualCompletionInCodePhase(index, steps.get(index).description());
                }
            }
            for (int index : indices) {
                steps.set(index, new ImplementationPlan.TechStep(steps.get(index).description(), completed));
            }
            updated = currentPlan.withTechnicalSteps(steps);
            this.currentPlan = updated;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updated);
        String statusWord = "incomplete";
        if (completed) {
            statusWord = "completed";
        }
        return String.format("Marked steps %s %s. %s", Arrays.toString(indices), statusWord,
                updated.formatRemainingIncompleteSteps());
    }

    private void ensureActivePlanLocked() {
        if (currentPlan == null) {
            throw new IllegalStateException("Error: No active implementation plan.");
        }
    }

    private static void validateStepIndex(int index, int stepCount) {
        if (stepCount <= 0) {
            throw new IllegalArgumentException("Error: Current plan has no technical steps.");
        }
        if (index < 0 || index >= stepCount) {
            throw new IllegalArgumentException(String
                    .format("Error: Invalid step index %d. Valid 0-based indices are 0..%d.", index, stepCount - 1));
        }
    }

    private static void validateManualCompletionInCodePhase(int index, String description) {
        if (ImplementationPlan.isCompileStep(description) || ImplementationPlan.isUnitTestStep(description)) {
            throw new IllegalStateException("Error: Step " + index + " ('" + description
                    + "') cannot be marked completed manually in CODE phase. Run buildToolService.compile(), buildToolService.runTests(), or buildToolService.buildAndTest() to satisfy build and test verification steps.");
        }
        if (ImplementationPlan.isCodeReviewStep(description)) {
            throw new IllegalStateException("Error: Step " + index + " ('" + description
                    + "') cannot be marked completed in CODE phase. Automated code review is verified in the REVIEW phase.");
        }
    }

    public ImplementationPlan getCurrentPlan() {
        return currentPlan;
    }

    @AgentDoc(value = "Requests transition to the development (CODE) phase.", phases = {"PLAN"}, examples = {
            "planService.requestDevelopment('Plan is complete and ready for implementation')"})
    public String requestDevelopment(
            @AgentDoc("The reason for requesting transition to the CODE phase") String reason) {
        requirePhase("requestDevelopment");
        return routeToPhase(WorkflowPhase.CODE, reason);
    }

    @AgentDoc(value = "Requests transition to the review phase.", phases = {"CODE"}, examples = {
            "planService.requestReview('All implementation steps and unit tests are complete')"})
    public String requestReview(@AgentDoc("The reason for requesting transition to the REVIEW phase") String reason) {
        requirePhase("requestReview");
        return routeToPhase(WorkflowPhase.REVIEW, reason);
    }

    @AgentDoc(value = "Requests transition back to development to address fixes.", phases = {"REVIEW"}, examples = {
            "planService.requestFixes('Found failing edge case in unit test')"})
    public String requestFixes(@AgentDoc("The reason for requesting transition back to the CODE phase") String reason) {
        requirePhase("requestFixes");
        return routeToPhase(WorkflowPhase.CODE, reason);
    }

    @AgentDoc(value = "Signals completion of the current cycle and returns to planning.", phases = {
            "REVIEW"}, examples = {
                    "planService.requestCompletion('All requirements and quality gates verified', 'feat: summary')"})
    public String requestCompletion(
            @AgentDoc("The structured Final Review Notes (Markdown audit summary covering Requirements, Code Quality/Guidelines, and Test Coverage)") String reason) {
        return requestCompletion(reason, null);
    }

    @AgentDoc(value = "Signals completion of the current cycle with a proposed Conventional Commit message and returns to planning.", phases = {
            "REVIEW"}, examples = {
                    "planService.requestCompletion('All requirements and quality gates verified', 'feat: add branch comparison tools')"})
    public String requestCompletion(
            @AgentDoc("The structured Final Review Notes (Markdown audit summary covering Requirements, Code Quality/Guidelines, and Test Coverage)") String reason,
            @AgentDoc("The proposed Conventional Commit message") String commitMessage) {
        requirePhase("requestCompletion");
        return routeToPhase(WorkflowPhase.PLAN, reason, commitMessage);
    }

    @AgentDoc(value = "Requests transition back to the planning phase (PLAN) due to technical blockers or needed revisions.", phases = {
            "CODE"}, examples = {"planService.requestPlanRevision('Need architectural adjustment before proceeding')"})
    public String requestPlanRevision(@AgentDoc("The reason for requesting a plan revision") String reason) {
        requirePhase("requestPlanRevision");
        return routeToPhase(WorkflowPhase.PLAN, reason);
    }

    String routeToPhase(WorkflowPhase nextPhase, String reason) {
        return routeToPhase(nextPhase, reason, null);
    }

    private String routeToPhase(WorkflowPhase nextPhase, String reason, String commitMessage) {
        if (nextPhase == null) {
            throw new IllegalArgumentException(
                    "Error: Invalid phase name: null. Valid phases are: PLAN, CODE, REVIEW.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Error: You must provide a descriptive 'reason' for requesting a phase transition.");
        }

        WorkflowPhase oldPhase;
        boolean autonomous;
        ImplementationPlan updatedPlan = null;
        boolean planChanged = false;
        ImplementationPlan planForSummary;
        PersistSnapshot snapshot;

        synchronized (stateLock) {
            if (nextPhase == currentPhase) {
                throw new IllegalStateException("Already in phase: " + nextPhase);
            }
            oldPhase = currentPhase;
            TransitionRule rule = TRANSITION_RULES.get(new TransitionKey(currentPhase, nextPhase));
            if (rule == null) {
                throw new IllegalStateException("Error: Cannot jump from " + currentPhase + " to " + nextPhase
                        + ". Only single-step transitions to adjacent phases are allowed.");
            }
            if (currentPhase == WorkflowPhase.PLAN && nextPhase == WorkflowPhase.CODE && currentPlan == null) {
                throw new IllegalStateException(
                        "Error: Cannot advance to CODE. Implementation Plan is missing. Call submitPlan first.");
            }
            if (rule.requiresApprovedPlan() && (currentPlan == null || !currentPlan.userApproved())) {
                throw new IllegalStateException("Error: Cannot transition from " + currentPhase + " to " + nextPhase
                        + ". An approved Implementation Plan is required.");
            }

            verifyStepCompletionGates(currentPhase, nextPhase);
            if (currentPhase == WorkflowPhase.REVIEW && nextPhase == WorkflowPhase.CODE) {
                if (prepareFixesOnCurrentPlanLocked(reason)) {
                    planChanged = true;
                    updatedPlan = currentPlan;
                }
            }
            if (currentPhase == WorkflowPhase.REVIEW && nextPhase == WorkflowPhase.PLAN && currentPlan != null) {
                String nextCommitMsg = currentPlan.commitMessage();
                if (StringUtils.isNotBlank(commitMessage)) {
                    nextCommitMsg = commitMessage;
                }
                ImplementationPlan updated = currentPlan.withReviewOutcome(reason, nextCommitMsg);
                this.currentPlan = updated;
                updatedPlan = updated;
                planChanged = true;
            }

            autonomous = rule.autonomous();
            if (autonomous) {
                addHandoffLogEntryLocked(OffsetDateTime.now().toString(), "System",
                        String.format("%s -> %s: %s", oldPhase, nextPhase, reason));
                applyPhaseStateTransitionLocked(nextPhase, false);
                planChanged = true;
                updatedPlan = currentPlan;
            } else {
                this.pendingPhase = nextPhase;
                this.pendingReason = reason;
            }
            planForSummary = currentPlan;
            snapshot = capturePersistSnapshotLocked();
        }

        enqueueWorkflowDiskOperation(snapshot);
        if (planChanged) {
            publishPlanUpdated(updatedPlan);
        }
        if (autonomous) {
            publishPhaseChanged(nextPhase);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new WorkflowTransitionEvent(oldPhase, nextPhase, reason, true));
            }
            return String.format(
                    "Autonomous transition from %s to %s registered. Reason: %s. Provide a brief concluding summary to the user without calling any additional tools.",
                    oldPhase, nextPhase, reason);
        }

        publishTransitionRequested(oldPhase, nextPhase, reason);

        String summary = formatPlanSummary(planForSummary);
        String message = String.format(
                "**Transition Request: %s**%n%n**Reason:** %s%n%nTransition request queued for user approval. Provide a brief concluding summary to the user without calling any additional tools.",
                formatApprovalLabel(oldPhase, nextPhase), reason);
        if (StringUtils.isNotBlank(summary)) {
            return String.format("%s%n%n%s", summary, message);
        }
        return message;
    }

    private boolean prepareFixesOnCurrentPlanLocked(String reason) {
        if (currentPlan == null) {
            return false;
        }
        ImplementationPlan updated = currentPlan
                .withVerificationStepCompleted(ImplementationPlan.STEP_CODE_REVIEW, false)
                .withReviewOutcome(null, currentPlan.commitMessage());
        if (updated.collectIncompleteSteps(true).isEmpty()) {
            List<ImplementationPlan.TechStep> steps = new ArrayList<>(updated.technicalSteps());
            insertStepBeforeVerificationGates(steps, "Address review findings: " + reason.trim());
            updated = updated.withTechnicalSteps(steps);
        }
        this.currentPlan = updated;
        return true;
    }

    private static int insertStepBeforeVerificationGates(List<ImplementationPlan.TechStep> steps, String description) {
        int insertIndex = steps.size();
        while (insertIndex > 0
                && ImplementationPlan.isMandatoryVerificationStep(steps.get(insertIndex - 1).description())) {
            insertIndex--;
        }
        steps.add(insertIndex, new ImplementationPlan.TechStep(description, false));
        return insertIndex;
    }

    private void verifyStepCompletionGates(WorkflowPhase from, WorkflowPhase to) {
        if (currentPlan == null) {
            return;
        }
        if (from == WorkflowPhase.CODE && to == WorkflowPhase.REVIEW) {
            List<String> incomplete = currentPlan.collectIncompleteSteps(true);
            if (!incomplete.isEmpty()) {
                throw new IllegalStateException(
                        "Error: Cannot transition from CODE to REVIEW. All implementation and build/test verification steps must be marked completed before requesting review. Incomplete steps: ["
                                + String.join(", ", incomplete)
                                + "]. Mark implementation steps via markStepCompleted or markStepsCompleted, and run buildToolService.compile() and buildToolService.runTests() (or buildToolService.buildAndTest()) for build/test verification steps.");
            }
        } else if (from == WorkflowPhase.REVIEW && to == WorkflowPhase.PLAN) {
            List<String> incomplete = currentPlan.collectIncompleteSteps(false);
            if (!incomplete.isEmpty()) {
                throw new IllegalStateException(
                        "Error: Cannot transition from REVIEW to PLAN. All technical and verification steps (including code review) must be marked completed before requesting completion. Incomplete steps: ["
                                + String.join(", ", incomplete)
                                + "]. Mark them completed via markStepCompleted or markStepsCompleted, or call requestFixes to return to CODE.");
            }
        }
    }

    private void archiveCurrentPlan(ImplementationPlan plan, String timestamp) {
        if (plan == null || projectService == null || !projectService.hasActiveProject()) {
            return;
        }
        try {
            Path archiveDir = projectService.resolveProjectDataPath(ARCHIVE_PLANS_SUBDIR);
            PathUtils.ArchiveFileTarget archiveTarget = PathUtils.prepareArchiveTarget(archiveDir, timestamp,
                    plan.title(), MAX_PLAN_SLUG_LENGTH, "plan");
            String archivedMarkdown = plan.toArchivedMarkdown(archiveTarget.id(), projectService.getProjectName(),
                    ProjectService.resolveNormalizedProjectPath(projectService));
            Files.writeString(archiveTarget.file(), archivedMarkdown, StandardCharsets.UTF_8);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_PLAN,
                        archiveTarget.file().getFileName().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to archive completed implementation plan", e);
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
        ImplementationPlan planToArchive = null;
        String archiveTimestamp = null;
        ImplementationPlan updatedPlan;
        WorkflowPhase newPhase;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            if (pendingPhase == null) {
                return;
            }
            String timestamp = OffsetDateTime.now().toString();
            String user = EnvironmentService.resolveCurrentUser(environmentService);
            addHandoffLogEntryLocked(timestamp, user,
                    String.format("%s -> %s (User Approved): %s", currentPhase, pendingPhase, pendingReason));

            if (currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE && currentPlan != null) {
                this.currentPlan = currentPlan.withApproval(user, timestamp);
            }
            if (currentPhase == WorkflowPhase.REVIEW && pendingPhase == WorkflowPhase.PLAN && currentPlan != null) {
                planToArchive = currentPlan.withCompletion(user, timestamp);
                archiveTimestamp = timestamp;
                this.unassignedTaskUsage = ImplementationPlan.UsageSummary.EMPTY;
                this.currentPlan = null;
            }

            newPhase = pendingPhase;
            applyPhaseStateTransitionLocked(pendingPhase, planToArchive != null);
            updatedPlan = currentPlan;
            snapshot = capturePersistSnapshotLocked(planToArchive != null);
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updatedPlan);
        if (planToArchive != null) {
            ImplementationPlan capturedPlan = planToArchive;
            String capturedTimestamp = archiveTimestamp;
            if (SwingUtilities.isEventDispatchThread()) {
                CompletableFuture.runAsync(() -> {
                    archiveCurrentPlan(capturedPlan, capturedTimestamp);
                    publishPhaseChanged(newPhase);
                }, Edt.VIRTUAL_EXECUTOR);
                return;
            }
            archiveCurrentPlan(capturedPlan, capturedTimestamp);
        }
        publishPhaseChanged(newPhase);
    }

    public void rejectTransition() {
        WorkflowPhase phase;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            this.pendingPhase = null;
            this.pendingReason = null;
            phase = currentPhase;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishTransitionRequested(phase, null, null);
    }

    public void rejectCompletionAndReturnToCode(String reason) {
        String effectiveReason = StringUtils.defaultIfBlank(reason, "User requested additional changes.");
        ImplementationPlan updatedPlan;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            this.pendingPhase = null;
            this.pendingReason = null;
            prepareFixesOnCurrentPlanLocked(effectiveReason);
            String timestamp = OffsetDateTime.now().toString();
            String user = EnvironmentService.resolveCurrentUser(environmentService);
            addHandoffLogEntryLocked(timestamp, user,
                    String.format("REVIEW -> CODE (Completion Rejected): %s", effectiveReason.trim()));
            applyPhaseStateTransitionLocked(WorkflowPhase.CODE, false);
            updatedPlan = currentPlan;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updatedPlan);
        publishPhaseChanged(WorkflowPhase.CODE);
        publishTransitionRequested(WorkflowPhase.CODE, null, null);
    }

    private void applyPhaseStateTransitionLocked(WorkflowPhase targetPhase, boolean resetToPlan) {
        this.currentPhase = targetPhase;
        if (resetToPlan) {
            this.visitedPhases.clear();
            this.visitedPhases.add(WorkflowPhase.PLAN);
        } else {
            this.visitedPhases.add(targetPhase);
        }
        this.pendingPhase = null;
        this.pendingReason = null;
    }

    public Set<WorkflowPhase> getVisitedPhases() {
        synchronized (stateLock) {
            return Collections.unmodifiableSet(EnumSet.copyOf(visitedPhases));
        }
    }

    public void resetWorkflow() {
        resetWorkflowInternal(true, true);
    }

    private void resetWorkflowInternal(boolean deletePersistedFile, boolean publishEvents) {
        PersistSnapshot snapshot = null;
        synchronized (stateLock) {
            resetWorkflowStateLocked();
            if (deletePersistedFile) {
                snapshot = capturePersistSnapshotLocked(true);
            }
        }
        if (snapshot != null) {
            enqueueWorkflowDiskOperation(snapshot);
        }
        if (publishEvents) {
            publishPlanUpdated(null);
            publishPhaseChanged(WorkflowPhase.PLAN);
            publishTransitionRequested(WorkflowPhase.PLAN, null, null);
        }
    }

    private void resetWorkflowStateLocked() {
        this.pendingPhase = null;
        this.pendingReason = null;
        this.currentPhase = WorkflowPhase.PLAN;
        this.visitedPhases.clear();
        this.visitedPhases.add(WorkflowPhase.PLAN);
        this.unassignedTaskUsage = ImplementationPlan.UsageSummary.EMPTY;
        this.currentPlan = null;
    }

    private void restoreWorkflowForCurrentProject(boolean publishEvents) {
        awaitPendingPersistence();
        Path path = resolveActiveWorkflowPath();
        ActiveWorkflowState saved = PathUtils.readJsonOrDefault(path, ActiveWorkflowState.class, null);
        if (saved == null) {
            resetWorkflowInternal(false, publishEvents);
            return;
        }
        ImplementationPlan restoredPlan;
        WorkflowPhase restoredPhase;
        WorkflowPhase restoredPendingPhase;
        String restoredPendingReason;
        synchronized (stateLock) {
            this.currentPlan = saved.currentPlan();
            if (saved.currentPhase() != null && saved.currentPhase() != WorkflowPhase.EXPLORE) {
                this.currentPhase = saved.currentPhase();
            } else {
                this.currentPhase = WorkflowPhase.PLAN;
            }
            this.pendingPhase = saved.pendingPhase();
            this.pendingReason = saved.pendingReason();
            this.visitedPhases.clear();
            this.visitedPhases.add(WorkflowPhase.PLAN);
            if (saved.visitedPhases() != null) {
                for (WorkflowPhase phase : saved.visitedPhases()) {
                    if (phase != null && phase != WorkflowPhase.EXPLORE) {
                        this.visitedPhases.add(phase);
                    }
                }
            }
            this.visitedPhases.add(this.currentPhase);
            if (saved.unassignedTaskUsage() != null) {
                this.unassignedTaskUsage = saved.unassignedTaskUsage();
            } else {
                this.unassignedTaskUsage = ImplementationPlan.UsageSummary.EMPTY;
            }
            restoredPlan = this.currentPlan;
            restoredPhase = this.currentPhase;
            restoredPendingPhase = this.pendingPhase;
            restoredPendingReason = this.pendingReason;
        }
        if (publishEvents) {
            publishPlanUpdated(restoredPlan);
            publishPhaseChanged(restoredPhase);
            publishTransitionRequested(restoredPhase, restoredPendingPhase, restoredPendingReason);
        }
    }

    private Path resolveActiveWorkflowPath() {
        return ProjectService.resolveProjectDataPathSafely(projectService, ACTIVE_WORKFLOW_FILENAME);
    }

    private boolean hasActiveWorkflowStateToPersistLocked() {
        if (currentPlan != null || currentPhase != WorkflowPhase.PLAN || pendingPhase != null) {
            return true;
        }
        if (unassignedTaskUsage != null && unassignedTaskUsage.hasUsage()) {
            return true;
        }
        return visitedPhases.size() > 1;
    }

    private @Nullable PersistSnapshot capturePersistSnapshotLocked() {
        return capturePersistSnapshotLocked(false);
    }

    private @Nullable PersistSnapshot capturePersistSnapshotLocked(boolean forceDelete) {
        Path path = resolveActiveWorkflowPath();
        if (path == null) {
            return null;
        }
        if (forceDelete || !hasActiveWorkflowStateToPersistLocked()) {
            return new PersistSnapshot(path, null, true);
        }
        ActiveWorkflowState state = new ActiveWorkflowState(currentPlan, currentPhase, pendingPhase, pendingReason,
                List.copyOf(visitedPhases), unassignedTaskUsage);
        return new PersistSnapshot(path, state, false);
    }

    private CompletableFuture<Void> enqueueWorkflowDiskOperation(@Nullable PersistSnapshot snapshot) {
        if (snapshot == null || snapshot.path() == null) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (persistLock) {
            CompletableFuture<Void> next = lastPersistFuture.thenRunAsync(() -> writeWorkflowStateToDisk(snapshot),
                    Edt.VIRTUAL_EXECUTOR);
            lastPersistFuture = next;
            return next;
        }
    }

    private static void writeWorkflowStateToDisk(PersistSnapshot snapshot) {
        try {
            if (snapshot.deleteOnly()) {
                PathUtils.deleteIfExistsQuietly(snapshot.path());
                return;
            }
            PathUtils.writeJson(snapshot.path(), snapshot.state());
        } catch (Exception e) {
            log.warn("Failed to persist active workflow state", e);
        }
    }

    final void awaitPendingPersistence() {
        CompletableFuture<Void> pending;
        synchronized (persistLock) {
            pending = lastPersistFuture;
        }
        pending.join();
    }

    private void publishPhaseChanged(WorkflowPhase phase) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PhaseChangedEvent(phase));
        }
    }

    private void publishTransitionRequested(WorkflowPhase fromPhase, @Nullable WorkflowPhase targetPendingPhase,
            @Nullable String reason) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new WorkflowTransitionEvent(fromPhase, targetPendingPhase, reason, false));
        }
    }

    private void publishPlanUpdated(@Nullable ImplementationPlan plan) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PlanUpdatedEvent(plan));
        }
    }

    private String formatPlanSummary(ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        return """
                PLAN SUMMARY:

                """ + plan.toMarkdown(false).trim();
    }

    private void requirePhase(String methodName) {
        List<WorkflowPhase> allowedPhases = ALLOWED_PHASES_BY_METHOD.getOrDefault(methodName, List.of());
        WorkflowPhase current = getCurrentPhase();
        if (allowedPhases.contains(current)) {
            return;
        }
        throw new IllegalStateException("Method '" + methodName + "' is only allowed in phase(s) " + allowedPhases
                + ". Current phase: " + current);
    }

    private void addHandoffLogEntryLocked(String timestamp, String user, String message) {
        if (currentPlan != null) {
            this.currentPlan = currentPlan.withAddedHandoffLogEntry(timestamp, user, message);
        }
    }

    @AgentDoc(value = "Adds a high-level summary entry to the handoff log.", phases = {"PLAN", "CODE",
            "REVIEW"}, examples = {"planService.addHandoffLogEntry('Completed refactoring of service layer')"})
    public String addHandoffLogEntry(@AgentDoc("The summary entry to add to the handoff log") String entry) {
        requirePhase("addHandoffLogEntry");
        if (StringUtils.isBlank(entry)) {
            throw new IllegalArgumentException("Error: Handoff log entry cannot be null or blank.");
        }
        ImplementationPlan updated;
        PersistSnapshot snapshot;
        synchronized (stateLock) {
            requirePhase("addHandoffLogEntry");
            ensureActivePlanLocked();
            addHandoffLogEntryLocked(OffsetDateTime.now().toString(), "System", entry.trim());
            updated = currentPlan;
            snapshot = capturePersistSnapshotLocked();
        }
        enqueueWorkflowDiskOperation(snapshot);
        publishPlanUpdated(updated);
        return "Handoff log entry added.";
    }
}
