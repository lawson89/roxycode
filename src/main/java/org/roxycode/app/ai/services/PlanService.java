package org.roxycode.app.ai.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    static final String ARCHIVE_PLANS_SUBDIR = "archive/plans";
    static final int MAX_PLAN_SLUG_LENGTH = 40;

    public static final String APPROVAL_PLAN_TO_CODE = "Approve Plan and proceed to Code";
    public static final String APPROVAL_REVIEW_TO_PLAN = "Approve Feature Request as complete";
    public static final String APPROVAL_CODE_TO_PLAN = "Approve Plan Revision and return to Plan";

    public static final String STEP_COMPILE = ImplementationPlan.STEP_COMPILE;
    public static final String STEP_UNIT_TESTS = ImplementationPlan.STEP_UNIT_TESTS;
    public static final String STEP_CODE_REVIEW = ImplementationPlan.STEP_CODE_REVIEW;

    private record TransitionKey(WorkflowPhase from, WorkflowPhase to) {
    }

    private record TransitionRule(boolean autonomous, boolean requiresApprovedPlan) {
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
        return (from != null ? from.getDisplayName() : "") + " → " + (to != null ? to.getDisplayName() : "");
    }

    private static final Map<TransitionKey, TransitionRule> TRANSITION_RULES = Map.of(
            new TransitionKey(WorkflowPhase.PLAN, WorkflowPhase.CODE), new TransitionRule(false, false),
            new TransitionKey(WorkflowPhase.CODE, WorkflowPhase.REVIEW), new TransitionRule(true, true),
            new TransitionKey(WorkflowPhase.CODE, WorkflowPhase.PLAN), new TransitionRule(false, false),
            new TransitionKey(WorkflowPhase.REVIEW, WorkflowPhase.CODE), new TransitionRule(true, true),
            new TransitionKey(WorkflowPhase.REVIEW, WorkflowPhase.PLAN), new TransitionRule(false, true));

    private static final Map<String, List<WorkflowPhase>> ALLOWED_PHASES_BY_TOOL = Map.ofEntries(
            Map.entry("submitPlan", List.of(WorkflowPhase.PLAN)),
            Map.entry("addTechnicalStep", List.of(WorkflowPhase.PLAN, WorkflowPhase.CODE)),
            Map.entry("updateTechnicalStep", List.of(WorkflowPhase.PLAN, WorkflowPhase.CODE)),
            Map.entry("markStepCompleted", List.of(WorkflowPhase.CODE, WorkflowPhase.REVIEW)),
            Map.entry("markStepsCompleted", List.of(WorkflowPhase.CODE, WorkflowPhase.REVIEW)),
            Map.entry("markStepIncomplete", List.of(WorkflowPhase.CODE, WorkflowPhase.REVIEW)),
            Map.entry("markStepsIncomplete", List.of(WorkflowPhase.CODE, WorkflowPhase.REVIEW)),
            Map.entry("addHandoffLogEntry", List.of(WorkflowPhase.PLAN, WorkflowPhase.CODE, WorkflowPhase.REVIEW)),
            Map.entry("requestDevelopment", List.of(WorkflowPhase.PLAN)),
            Map.entry("requestReview", List.of(WorkflowPhase.CODE)),
            Map.entry("requestFixes", List.of(WorkflowPhase.REVIEW)),
            Map.entry("requestCompletion", List.of(WorkflowPhase.REVIEW)),
            Map.entry("requestPlanRevision", List.of(WorkflowPhase.CODE)));

    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProjectService projectService;
    private final GitService gitService;
    private volatile ImplementationPlan currentPlan;
    private volatile WorkflowPhase currentPhase = WorkflowPhase.PLAN;
    private final Set<WorkflowPhase> visitedPhases = EnumSet.of(WorkflowPhase.PLAN);
    private volatile WorkflowPhase pendingPhase;
    private volatile String pendingReason;

    public PlanService(EnvironmentService environmentService, ApplicationEventPublisher eventPublisher,
            ProjectService projectService, GitService gitService) {
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
        this.projectService = projectService;
        this.gitService = gitService;
    }

    public ToolCallback[] getToolCallbacksForPhase(WorkflowPhase phase) {
        if (phase == null || phase == WorkflowPhase.EXPLORE) {
            return new ToolCallback[0];
        }
        return Arrays.stream(ToolCallbacks.from(this)).filter(
                cb -> ALLOWED_PHASES_BY_TOOL.getOrDefault(cb.getToolDefinition().name(), List.of()).contains(phase))
                .toArray(ToolCallback[]::new);
    }

    public synchronized String submitPlan(ImplementationPlan plan) {
        requirePhase("submitPlan");
        if (plan == null) {
            throw new IllegalArgumentException("Error: Plan cannot be null.");
        }
        setCurrentPlan(plan.withNormalizedStepsFrom(currentPlan));
        return "Implementation plan submitted successfully with mandatory verification checklist items.";
    }

    @Tool(description = "Submits a project plan covering both functional requirements and technical implementation steps.")
    public synchronized String submitPlan(
            @ToolParam(description = "The concise title of the project or feature plan (max 50 characters).") String title,
            @ToolParam(description = "The high-level goal of the plan.") String goal,
            @ToolParam(description = "List of functional requirements.") List<String> requirements,
            @ToolParam(description = "List of technical implementation steps.") List<String> technicalSteps) {
        requirePhase("submitPlan");
        List<String> safeRequirements = requirements != null ? List.copyOf(requirements) : List.of();
        List<ImplementationPlan.TechStep> steps = technicalSteps != null
                ? technicalSteps.stream().map(s -> new ImplementationPlan.TechStep(s, false)).toList()
                : List.of();

        return submitPlan(new ImplementationPlan(title != null ? title : "", goal != null ? goal : "", safeRequirements,
                steps, false, null, null, null, null, null));
    }

    private static String requireNonBlankDescription(String description) {
        if (StringUtils.isBlank(description)) {
            throw new IllegalArgumentException("Error: Step description cannot be null or blank.");
        }
        return description.trim();
    }

    @Tool(description = "Adds a new technical implementation step to the current plan (inserted before trailing verification gates) without losing progress on completed steps.")
    public synchronized String addTechnicalStep(
            @ToolParam(description = "Description of the new technical implementation step.") String description) {
        requirePhase("addTechnicalStep");
        ensureActivePlan();
        String trimmed = requireNonBlankDescription(description);
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
        int insertIndex = steps.size();
        while (insertIndex > 0
                && ImplementationPlan.isMandatoryVerificationStep(steps.get(insertIndex - 1).description())) {
            insertIndex--;
        }
        steps.add(insertIndex, new ImplementationPlan.TechStep(trimmed, false));
        setCurrentPlan(currentPlan.withTechnicalSteps(steps));
        return String.format("Added technical step at index %d: '%s'. %s", insertIndex, trimmed,
                currentPlan.formatRemainingIncompleteSteps());
    }

    @Tool(description = "Updates the description of an existing technical step at the given 0-based index while preserving its completion status.")
    public synchronized String updateTechnicalStep(
            @ToolParam(description = "The 0-based index of the step to update.") int index,
            @ToolParam(description = "The updated description for the technical step.") String description) {
        requirePhase("updateTechnicalStep");
        ensureActivePlan();
        String trimmed = requireNonBlankDescription(description);
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
        validateStepIndex(index, steps.size());
        steps.set(index, new ImplementationPlan.TechStep(trimmed, steps.get(index).completed()));
        setCurrentPlan(currentPlan.withTechnicalSteps(steps));
        return String.format("Updated technical step %d to '%s'. %s", index, trimmed,
                currentPlan.formatRemainingIncompleteSteps());
    }

    public synchronized void approvePlan(String user, String timestamp) {
        if (currentPlan != null) {
            setCurrentPlan(currentPlan.withApproval(user, timestamp));
        }
    }

    public synchronized void completePlan(String user, String timestamp) {
        if (currentPlan != null) {
            setCurrentPlan(currentPlan.withCompletion(user, timestamp));
        }
    }

    @Tool(description = "Marks a specific technical implementation step as completed using its 0-based index. Call this immediately after a step is verified rather than deferring to the end of the phase.")
    public synchronized String markStepCompleted(
            @ToolParam(description = "The index of the step (0-based). Must be valid for the current plan.") int index) {
        requirePhase("markStepCompleted");
        return updateStepsStatus(List.of(index), true);
    }

    @Tool(description = "Marks multiple technical implementation steps as completed using their 0-based indices. Call this immediately after a batch of steps is verified rather than deferring to the end of the phase.")
    public synchronized String markStepsCompleted(
            @ToolParam(description = "List of 0-based step indices to mark as completed.") List<Integer> indices) {
        requirePhase("markStepsCompleted");
        return updateStepsStatus(indices, true);
    }

    @Tool(description = "Marks a technical implementation step as incomplete using its 0-based index.")
    public synchronized String markStepIncomplete(
            @ToolParam(description = "The index of the step (0-based).") int index) {
        requirePhase("markStepIncomplete");
        return updateStepsStatus(List.of(index), false);
    }

    @Tool(description = "Marks multiple technical implementation steps as incomplete using their 0-based indices.")
    public synchronized String markStepsIncomplete(
            @ToolParam(description = "List of 0-based step indices to mark as incomplete.") List<Integer> indices) {
        requirePhase("markStepsIncomplete");
        return updateStepsStatus(indices, false);
    }

    private synchronized String updateStepsStatus(List<Integer> indices, boolean completed) {
        ensureActivePlan();
        if (indices == null || indices.isEmpty()) {
            throw new IllegalArgumentException("Error: Step indices cannot be null or empty.");
        }
        List<ImplementationPlan.TechStep> steps = new ArrayList<>(currentPlan.technicalSteps());
        for (Integer index : indices) {
            if (index == null) {
                throw new IllegalArgumentException("Error: Step index cannot be null.");
            }
            validateStepIndex(index, steps.size());
        }
        for (Integer index : indices) {
            steps.set(index, new ImplementationPlan.TechStep(steps.get(index).description(), completed));
        }
        setCurrentPlan(currentPlan.withTechnicalSteps(steps));
        return String.format("Marked steps %s %s. %s", indices, completed ? "completed" : "incomplete",
                currentPlan.formatRemainingIncompleteSteps());
    }

    private void ensureActivePlan() {
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

    public ImplementationPlan getCurrentPlan() {
        return currentPlan;
    }

    public synchronized void setCurrentPlan(ImplementationPlan plan) {
        this.currentPlan = plan;
        publishPlanUpdated();
    }

    public synchronized void clearPlan() {
        setCurrentPlan(null);
    }

    @Tool(description = "Requests transition to the development (CODE) phase.")
    public synchronized String requestDevelopment(
            @ToolParam(description = "The reason for requesting transition to the CODE phase.") String reason) {
        requirePhase("requestDevelopment");
        return routeToPhase(WorkflowPhase.CODE, reason);
    }

    @Tool(description = "Requests transition to the review phase.")
    public synchronized String requestReview(
            @ToolParam(description = "The reason for requesting transition to the REVIEW phase.") String reason) {
        requirePhase("requestReview");
        return routeToPhase(WorkflowPhase.REVIEW, reason);
    }

    @Tool(description = "Requests transition back to development to address fixes.")
    public synchronized String requestFixes(
            @ToolParam(description = "The reason for requesting transition back to the CODE phase.") String reason) {
        requirePhase("requestFixes");
        return routeToPhase(WorkflowPhase.CODE, reason);
    }

    @Tool(description = "Signals completion of the current cycle and returns to planning.")
    public synchronized String requestCompletion(
            @ToolParam(description = "The reason for completing the current cycle.") String reason) {
        requirePhase("requestCompletion");
        return routeToPhase(WorkflowPhase.PLAN, reason);
    }

    @Tool(description = "Requests transition back to the planning phase (PLAN) due to technical blockers or needed revisions.")
    public synchronized String requestPlanRevision(
            @ToolParam(description = "The reason for requesting a plan revision.") String reason) {
        requirePhase("requestPlanRevision");
        return routeToPhase(WorkflowPhase.PLAN, reason);
    }

    public synchronized String routeToPhase(WorkflowPhase nextPhase, String reason) {
        if (nextPhase == null) {
            throw new IllegalArgumentException(
                    "Error: Invalid phase name: null. Valid phases are: PLAN, CODE, REVIEW.");
        }
        if (nextPhase == currentPhase) {
            throw new IllegalStateException("Already in phase: " + nextPhase);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Error: You must provide a descriptive 'reason' for requesting a phase transition.");
        }

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

        if (rule.autonomous()) {
            WorkflowPhase oldPhase = currentPhase;
            addHandoffLogEntry(OffsetDateTime.now().toString(), "System",
                    String.format("%s -> %s: %s", oldPhase, nextPhase, reason));
            this.currentPhase = nextPhase;
            this.visitedPhases.add(nextPhase);
            publishPhaseChanged();
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new WorkflowTransitionEvent(oldPhase, nextPhase, reason, true));
            }
            return String.format(
                    "Autonomous transition from %s to %s registered. Reason: %s. Provide a brief concluding summary to the user without calling any additional tools.",
                    oldPhase, nextPhase, reason);
        }

        this.pendingPhase = nextPhase;
        this.pendingReason = reason;
        publishTransitionRequested();

        String summary = formatPlanSummary(currentPlan);
        String message = String.format(
                "**Transition Request: %s**%n%n**Reason:** %s%n%nTransition request queued for user approval. Provide a brief concluding summary to the user without calling any additional tools.",
                formatApprovalLabel(currentPhase, nextPhase), reason);
        return StringUtils.isNotBlank(summary) ? String.format("%s%n%n%s", summary, message) : message;
    }

    private void verifyStepCompletionGates(WorkflowPhase from, WorkflowPhase to) {
        if (currentPlan == null)
            return;
        if (from == WorkflowPhase.CODE && to == WorkflowPhase.REVIEW) {
            List<String> incomplete = currentPlan.collectIncompleteSteps(true);
            if (!incomplete.isEmpty()) {
                throw new IllegalStateException(
                        "Error: Cannot transition from CODE to REVIEW. All implementation and build/test verification steps must be marked completed before requesting review. Incomplete steps: ["
                                + String.join(", ", incomplete)
                                + "]. Complete and mark them via markStepCompleted or markStepsCompleted first.");
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
        if (plan == null || projectService == null || !projectService.hasActiveProject())
            return;
        try {
            Path archiveDir = projectService.resolveProjectDataPath(ARCHIVE_PLANS_SUBDIR);
            Files.createDirectories(archiveDir);
            String baseId = formatArchiveTimestamp(timestamp) + "-" + sanitizePlanTitle(plan.title());
            PathUtils.ArchiveFileTarget archiveTarget = PathUtils.resolveUniqueArchiveFile(archiveDir, baseId);

            Path projectRoot = projectService.getCurrentProjectRoot();
            String projectPath = projectRoot != null ? projectRoot.toAbsolutePath().normalize().toString() : "";
            String archivedMarkdown = plan.toArchivedMarkdown(archiveTarget.id(), projectService.getProjectName(),
                    projectPath, getGitStatusSafely());
            Files.writeString(archiveTarget.file(), archivedMarkdown, StandardCharsets.UTF_8);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_PLAN,
                        archiveTarget.file().getFileName().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to archive completed implementation plan", e);
        }
    }

    private String getGitStatusSafely() {
        if (gitService == null)
            return null;
        try {
            return gitService.getStatus();
        } catch (Exception e) {
            log.debug("Failed to retrieve git status for archived plan", e);
            return null;
        }
    }

    static String formatArchiveTimestamp(String isoTimestamp) {
        return PathUtils.formatArchiveTimestamp(isoTimestamp);
    }

    static String sanitizePlanTitle(String title) {
        return PathUtils.sanitizeSlug(title, MAX_PLAN_SLUG_LENGTH, "plan");
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
        synchronized (this) {
            if (pendingPhase == null)
                return;
            String timestamp = OffsetDateTime.now().toString();
            String user = environmentService != null ? environmentService.getCurrentUser() : "User";
            addHandoffLogEntry(timestamp, user,
                    String.format("%s -> %s (User Approved): %s", currentPhase, pendingPhase, pendingReason));

            if (currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE) {
                approvePlan(user, timestamp);
            }
            if (currentPhase == WorkflowPhase.REVIEW && pendingPhase == WorkflowPhase.PLAN && currentPlan != null) {
                completePlan(user, timestamp);
                planToArchive = currentPlan;
                archiveTimestamp = timestamp;
                clearPlan();
            }

            this.currentPhase = pendingPhase;
            this.visitedPhases.add(pendingPhase);
            this.pendingPhase = null;
            this.pendingReason = null;
        }
        if (planToArchive != null) {
            ImplementationPlan capturedPlan = planToArchive;
            String capturedTimestamp = archiveTimestamp;
            if (SwingUtilities.isEventDispatchThread()) {
                CompletableFuture.runAsync(() -> {
                    archiveCurrentPlan(capturedPlan, capturedTimestamp);
                    publishPhaseChanged();
                });
                return;
            }
            archiveCurrentPlan(capturedPlan, capturedTimestamp);
        }
        publishPhaseChanged();
    }

    public synchronized void rejectTransition() {
        this.pendingPhase = null;
        this.pendingReason = null;
        publishTransitionRequested();
    }

    public synchronized Set<WorkflowPhase> getVisitedPhases() {
        return Collections.unmodifiableSet(EnumSet.copyOf(visitedPhases));
    }

    public synchronized void resetWorkflow() {
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
            eventPublisher.publishEvent(new WorkflowTransitionEvent(currentPhase, pendingPhase, pendingReason, false));
        }
    }

    private void publishPlanUpdated() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PlanUpdatedEvent(currentPlan));
        }
    }

    private String formatPlanSummary(ImplementationPlan plan) {
        return plan == null ? "" : """
                PLAN SUMMARY:

                """ + plan.toMarkdown(false).trim();
    }

    private void requirePhase(String methodName) {
        List<WorkflowPhase> allowedPhases = ALLOWED_PHASES_BY_TOOL.getOrDefault(methodName, List.of());
        WorkflowPhase current = getCurrentPhase();
        if (allowedPhases.contains(current)) {
            return;
        }
        throw new IllegalStateException("Method '" + methodName + "' is only allowed in phase(s) " + allowedPhases
                + ". Current phase: " + current);
    }

    public synchronized void addHandoffLogEntry(String timestamp, String user, String message) {
        if (currentPlan != null) {
            setCurrentPlan(currentPlan.withAddedHandoffLogEntry(timestamp, user, message));
        }
    }

    @Tool(description = "Adds a high-level summary entry to the handoff log.")
    public synchronized String addHandoffLogEntry(
            @ToolParam(description = "The summary entry to add to the handoff log.") String entry) {
        requirePhase("addHandoffLogEntry");
        ensureActivePlan();
        if (StringUtils.isBlank(entry)) {
            throw new IllegalArgumentException("Error: Handoff log entry cannot be null or blank.");
        }
        addHandoffLogEntry(OffsetDateTime.now().toString(), "System", entry.trim());
        return "Handoff log entry added.";
    }
}
