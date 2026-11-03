package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.roxycode.app.util.TokenUtils;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public record ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
        boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
        List<LogEntry> handoffLog, List<FileChange> fileChanges, UsageSummary usageSummary) {

    public static final String NO_FILES_CHANGED = "No files changed";
    public static final String STEP_COMPILE = "Code compiles successfully";
    public static final String STEP_UNIT_TESTS = "Unit tests pass";
    public static final String STEP_CODE_REVIEW = "Automated code review passes";
    private static final String CODE_FENCE = String.valueOf((char) 96).repeat(3);

    public record TechStep(String description, boolean completed) {
    }

    public record FileChange(String path, FileChangedEvent.ChangeType changeType) {
        public FileChange {
            path = PathUtils.normalizeRelativePath(path);
            changeType = Objects.requireNonNullElse(changeType, FileChangedEvent.ChangeType.MODIFIED);
        }
    }

    public record LogEntry(String timestamp, String user, String message) {
        private static final int TIMESTAMP_LENGTH = 19;

        public String formattedTimestamp() {
            if (timestamp != null && timestamp.length() > TIMESTAMP_LENGTH) {
                return PathUtils.formatTimestampDisplay(timestamp, TIMESTAMP_LENGTH, "");
            }
            return StringUtils.defaultString(timestamp);
        }
    }

    public record PhaseUsage(int modelCalls, long totalTokens, double estimatedCostUsd) {

        public static final PhaseUsage EMPTY = new PhaseUsage(0, 0L, 0.0);

        public PhaseUsage {
            modelCalls = Math.max(0, modelCalls);
            totalTokens = Math.max(0L, totalTokens);
            estimatedCostUsd = Math.max(0.0, estimatedCostUsd);
        }

        public boolean hasUsage() {
            return modelCalls > 0 || totalTokens > 0L || estimatedCostUsd > 0.0;
        }

        public PhaseUsage add(long tokens, double costUsd) {
            return new PhaseUsage(modelCalls + 1, totalTokens + Math.max(0L, tokens),
                    estimatedCostUsd + Math.max(0.0, costUsd));
        }

        public PhaseUsage merge(PhaseUsage other) {
            if (other == null || !other.hasUsage()) {
                return this;
            }
            if (!this.hasUsage()) {
                return other;
            }
            return new PhaseUsage(modelCalls + other.modelCalls, totalTokens + other.totalTokens,
                    estimatedCostUsd + other.estimatedCostUsd);
        }
    }

    public record UsageSummary(int modelCalls, long promptTokens, long cachedInputTokens, long uncachedInputTokens,
            long outputTokens, long thoughtsTokens, long totalTokens, double uncachedInputCostUsd,
            double cachedInputCostUsd, double outputCostUsd, double thoughtsCostUsd, double estimatedCostUsd,
            PhaseUsage planPhaseUsage, PhaseUsage codePhaseUsage, PhaseUsage reviewPhaseUsage) {

        public static final UsageSummary EMPTY = new UsageSummary(0, 0L, 0L, 0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0,
                PhaseUsage.EMPTY, PhaseUsage.EMPTY, PhaseUsage.EMPTY);

        public UsageSummary {
            modelCalls = Math.max(0, modelCalls);
            promptTokens = Math.max(0L, promptTokens);
            cachedInputTokens = Math.max(0L, cachedInputTokens);
            uncachedInputTokens = Math.max(0L, uncachedInputTokens);
            outputTokens = Math.max(0L, outputTokens);
            thoughtsTokens = Math.max(0L, thoughtsTokens);
            totalTokens = Math.max(0L, totalTokens);
            uncachedInputCostUsd = Math.max(0.0, uncachedInputCostUsd);
            cachedInputCostUsd = Math.max(0.0, cachedInputCostUsd);
            outputCostUsd = Math.max(0.0, outputCostUsd);
            thoughtsCostUsd = Math.max(0.0, thoughtsCostUsd);
            estimatedCostUsd = Math.max(0.0, estimatedCostUsd);
            planPhaseUsage = Objects.requireNonNullElse(planPhaseUsage, PhaseUsage.EMPTY);
            codePhaseUsage = Objects.requireNonNullElse(codePhaseUsage, PhaseUsage.EMPTY);
            reviewPhaseUsage = Objects.requireNonNullElse(reviewPhaseUsage, PhaseUsage.EMPTY);
        }

        public UsageSummary(int modelCalls, long promptTokens, long cachedInputTokens, long uncachedInputTokens,
                long outputTokens, long thoughtsTokens, long totalTokens, double estimatedCostUsd) {
            this(modelCalls, promptTokens, cachedInputTokens, uncachedInputTokens, outputTokens, thoughtsTokens,
                    totalTokens, 0.0, 0.0, 0.0, 0.0, estimatedCostUsd, PhaseUsage.EMPTY, PhaseUsage.EMPTY,
                    PhaseUsage.EMPTY);
        }

        public boolean hasUsage() {
            return modelCalls > 0 || totalTokens > 0L;
        }

        public long outputAndThinkingTokens() {
            return outputTokens + thoughtsTokens;
        }

        public double inputCostUsd() {
            return uncachedInputCostUsd + cachedInputCostUsd;
        }

        public double outputAndThinkingCostUsd() {
            return outputCostUsd + thoughtsCostUsd;
        }

        public int cacheHitPercent() {
            return TokenUtils.computeCacheHitPercent(cachedInputTokens, promptTokens);
        }

        public UsageSummary addEvent(TokenUsageEvent event) {
            if (event == null) {
                return this;
            }
            return addEvent(event, event.phase());
        }

        public UsageSummary addEvent(TokenUsageEvent event, WorkflowPhase fallbackPhase) {
            if (event == null) {
                return this;
            }
            WorkflowPhase effectivePhase = event.phase();
            if (effectivePhase == null) {
                effectivePhase = fallbackPhase;
            }
            long eventTokens = Math.max(0, event.totalTokens());
            double eventCost = Math.max(0.0, event.estimatedCostUsd());
            PhaseUsage nextPlan = planPhaseUsage;
            PhaseUsage nextCode = codePhaseUsage;
            PhaseUsage nextReview = reviewPhaseUsage;
            if (effectivePhase == WorkflowPhase.CODE) {
                nextCode = codePhaseUsage.add(eventTokens, eventCost);
            } else if (effectivePhase == WorkflowPhase.REVIEW) {
                nextReview = reviewPhaseUsage.add(eventTokens, eventCost);
            } else if (effectivePhase == WorkflowPhase.PLAN) {
                nextPlan = planPhaseUsage.add(eventTokens, eventCost);
            }
            return new UsageSummary(modelCalls + 1, promptTokens + Math.max(0, event.promptTokens()),
                    cachedInputTokens + Math.max(0, event.cachedInputTokens()),
                    uncachedInputTokens + Math.max(0, event.uncachedInputTokens()),
                    outputTokens + Math.max(0, event.candidatesTokens()),
                    thoughtsTokens + Math.max(0, event.thoughtsTokens()), totalTokens + eventTokens,
                    uncachedInputCostUsd + Math.max(0.0, event.uncachedInputCostUsd()),
                    cachedInputCostUsd + Math.max(0.0, event.cachedInputCostUsd()),
                    outputCostUsd + Math.max(0.0, event.outputCostUsd()),
                    thoughtsCostUsd + Math.max(0.0, event.thoughtsCostUsd()), estimatedCostUsd + eventCost, nextPlan,
                    nextCode, nextReview);
        }

        public UsageSummary merge(UsageSummary other) {
            if (other == null || !other.hasUsage()) {
                return this;
            }
            if (!this.hasUsage()) {
                return other;
            }
            return new UsageSummary(modelCalls + other.modelCalls, promptTokens + other.promptTokens,
                    cachedInputTokens + other.cachedInputTokens, uncachedInputTokens + other.uncachedInputTokens,
                    outputTokens + other.outputTokens, thoughtsTokens + other.thoughtsTokens,
                    totalTokens + other.totalTokens, uncachedInputCostUsd + other.uncachedInputCostUsd,
                    cachedInputCostUsd + other.cachedInputCostUsd, outputCostUsd + other.outputCostUsd,
                    thoughtsCostUsd + other.thoughtsCostUsd, estimatedCostUsd + other.estimatedCostUsd,
                    planPhaseUsage.merge(other.planPhaseUsage), codePhaseUsage.merge(other.codePhaseUsage),
                    reviewPhaseUsage.merge(other.reviewPhaseUsage));
        }
    }

    public ImplementationPlan {
        requirements = List.copyOf(Objects.requireNonNullElse(requirements, List.of()));
        technicalSteps = List.copyOf(Objects.requireNonNullElse(technicalSteps, List.of()));
        handoffLog = List.copyOf(Objects.requireNonNullElse(handoffLog, List.of()));
        fileChanges = List.copyOf(Objects.requireNonNullElse(fileChanges, List.of()));
        usageSummary = Objects.requireNonNullElse(usageSummary, UsageSummary.EMPTY);
    }

    public ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
            boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
            List<LogEntry> handoffLog, List<FileChange> fileChanges) {
        this(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp, approvedBy, completedBy,
                completedOn, handoffLog, fileChanges, UsageSummary.EMPTY);
    }

    public ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
            boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
            List<LogEntry> handoffLog) {
        this(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp, approvedBy, completedBy,
                completedOn, handoffLog, List.of(), UsageSummary.EMPTY);
    }

    public static String normalizeStepDescription(String description) {
        return StringUtils.stripEnd(StringUtils.trimToEmpty(description), ".!;: " + (char) 9 + (char) 13 + (char) 10)
                .toLowerCase(Locale.ROOT);
    }

    public static boolean isCompileStep(String description) {
        return normalizeStepDescription(description).equals(STEP_COMPILE.toLowerCase(Locale.ROOT));
    }

    public static boolean isUnitTestStep(String description) {
        return normalizeStepDescription(description).equals(STEP_UNIT_TESTS.toLowerCase(Locale.ROOT));
    }

    public static boolean isCodeReviewStep(String description) {
        return normalizeStepDescription(description).equals(STEP_CODE_REVIEW.toLowerCase(Locale.ROOT));
    }

    public static boolean isMandatoryVerificationStep(String description) {
        return isCompileStep(description) || isUnitTestStep(description) || isCodeReviewStep(description);
    }

    public Set<String> collectCompletedNonVerificationSteps() {
        if (technicalSteps.isEmpty()) {
            return Set.of();
        }
        Set<String> completed = new HashSet<>();
        for (TechStep step : technicalSteps) {
            if (step.completed() && !isMandatoryVerificationStep(step.description())) {
                completed.add(normalizeStepDescription(step.description()));
            }
        }
        return Collections.unmodifiableSet(completed);
    }

    public ImplementationPlan withNormalizedStepsFrom(ImplementationPlan previousPlan) {
        Set<String> previouslyCompletedSteps = Set.of();
        if (previousPlan != null) {
            previouslyCompletedSteps = previousPlan.collectCompletedNonVerificationSteps();
        }
        List<LogEntry> preservedLog = handoffLog;
        if (handoffLog.isEmpty() && previousPlan != null) {
            preservedLog = previousPlan.handoffLog();
        }
        List<FileChange> preservedChanges = fileChanges;
        if (fileChanges.isEmpty() && previousPlan != null) {
            preservedChanges = previousPlan.fileChanges();
        }
        UsageSummary preservedUsage = usageSummary;
        if (previousPlan != null && previousPlan.usageSummary().hasUsage()) {
            preservedUsage = previousPlan.usageSummary().merge(usageSummary);
        }

        List<TechStep> steps = new ArrayList<>();
        boolean hasCompile = false;
        boolean hasTests = false;
        boolean hasReview = false;
        for (TechStep s : technicalSteps) {
            String desc = s.description();
            boolean preservedCompleted = s.completed() || (!isMandatoryVerificationStep(desc)
                    && previouslyCompletedSteps.contains(normalizeStepDescription(desc)));
            steps.add(new TechStep(desc, preservedCompleted));
            hasCompile |= isCompileStep(desc);
            hasTests |= isUnitTestStep(desc);
            hasReview |= isCodeReviewStep(desc);
        }

        if (!hasCompile) {
            steps.add(new TechStep(STEP_COMPILE, false));
        }
        if (!hasTests) {
            steps.add(new TechStep(STEP_UNIT_TESTS, false));
        }
        if (!hasReview) {
            steps.add(new TechStep(STEP_CODE_REVIEW, false));
        }

        return withTechnicalSteps(steps).withHandoffLog(preservedLog).withFileChanges(preservedChanges)
                .withUsageSummary(preservedUsage);
    }

    public List<String> collectIncompleteSteps(boolean excludeCodeReviewStep) {
        List<String> incomplete = new ArrayList<>();
        for (int i = 0; i < technicalSteps.size(); i++) {
            TechStep step = technicalSteps.get(i);
            if (!step.completed() && (!excludeCodeReviewStep || !isCodeReviewStep(step.description()))) {
                incomplete.add(i + ": " + step.description());
            }
        }
        return incomplete;
    }

    public String formatRemainingIncompleteSteps() {
        List<String> remaining = collectIncompleteSteps(false);
        if (remaining.isEmpty()) {
            return "Remaining incomplete steps: none (all steps completed).";
        }
        return "Remaining incomplete steps: [" + String.join(", ", remaining) + "]";
    }

    public ImplementationPlan withTechnicalSteps(List<TechStep> newSteps) {
        return new ImplementationPlan(title, goal, requirements, newSteps, userApproved, approvalTimestamp, approvedBy,
                completedBy, completedOn, handoffLog, fileChanges, usageSummary);
    }

    public ImplementationPlan withVerificationStepCompleted(String stepDescription, boolean completed) {
        String normalizedTarget = normalizeStepDescription(stepDescription);
        if (normalizedTarget.isEmpty() || technicalSteps.isEmpty()) {
            return this;
        }
        List<TechStep> updatedSteps = new ArrayList<>(technicalSteps.size());
        boolean modified = false;
        for (TechStep step : technicalSteps) {
            if (normalizeStepDescription(step.description()).equals(normalizedTarget)) {
                if (step.completed() != completed) {
                    updatedSteps.add(new TechStep(step.description(), completed));
                    modified = true;
                } else {
                    updatedSteps.add(step);
                }
            } else {
                updatedSteps.add(step);
            }
        }
        if (!modified) {
            return this;
        }
        return withTechnicalSteps(updatedSteps);
    }

    public ImplementationPlan withApproval(String user, String timestamp) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, true, timestamp, user, completedBy,
                completedOn, handoffLog, fileChanges, usageSummary);
    }

    public ImplementationPlan withCompletion(String user, String timestamp) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, user, timestamp, handoffLog, fileChanges, usageSummary);
    }

    public ImplementationPlan withHandoffLog(List<LogEntry> newLog) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, newLog, fileChanges, usageSummary);
    }

    public ImplementationPlan withAddedHandoffLogEntry(String timestamp, String user, String message) {
        List<LogEntry> updatedLog = new ArrayList<>(handoffLog);
        updatedLog.add(new LogEntry(timestamp, user, message));
        return withHandoffLog(updatedLog);
    }

    public ImplementationPlan withFileChanges(List<FileChange> newChanges) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, handoffLog, newChanges, usageSummary);
    }

    public ImplementationPlan withUsageSummary(UsageSummary newUsage) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, handoffLog, fileChanges, newUsage);
    }

    public ImplementationPlan withRecordedTokenUsage(TokenUsageEvent event) {
        if (event == null) {
            return this;
        }
        return withRecordedTokenUsage(event, event.phase());
    }

    public ImplementationPlan withRecordedTokenUsage(TokenUsageEvent event, WorkflowPhase fallbackPhase) {
        if (event == null) {
            return this;
        }
        return withUsageSummary(usageSummary.addEvent(event, fallbackPhase));
    }

    public ImplementationPlan withRecordedFileChange(String rawPath, FileChangedEvent.ChangeType changeType) {
        String normalizedPath = PathUtils.normalizeRelativePath(rawPath);
        if (StringUtils.isBlank(normalizedPath)) {
            return this;
        }
        FileChangedEvent.ChangeType resolvedType = Objects.requireNonNullElse(changeType,
                FileChangedEvent.ChangeType.MODIFIED);
        List<FileChange> updated = new ArrayList<>(fileChanges);
        int existingIndex = -1;
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).path().equals(normalizedPath)) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex < 0) {
            updated.add(new FileChange(normalizedPath, resolvedType));
        } else {
            FileChangedEvent.ChangeType previousType = updated.get(existingIndex).changeType();
            FileChangedEvent.ChangeType nextType = resolvedType;
            if (previousType == FileChangedEvent.ChangeType.CREATED
                    && resolvedType == FileChangedEvent.ChangeType.MODIFIED) {
                nextType = FileChangedEvent.ChangeType.CREATED;
            }
            updated.set(existingIndex, new FileChange(normalizedPath, nextType));
        }
        return withFileChanges(updated);
    }

    public boolean hasMoveToCodeApproval() {
        return userApproved || StringUtils.isNotBlank(approvedBy) || StringUtils.isNotBlank(approvalTimestamp);
    }

    public boolean hasFinalAcceptance() {
        return StringUtils.isNotBlank(completedBy) || StringUtils.isNotBlank(completedOn);
    }

    public boolean hasApprovals() {
        return hasMoveToCodeApproval() || hasFinalAcceptance();
    }

    public String toMarkdown(boolean includeHandoffLog) {
        StringBuilder md = new StringBuilder("# Implementation Plan: ").append(StringUtils.defaultString(title))
                .append((char) 10).append((char) 10).append("**Goal:** ").append(StringUtils.defaultString(goal))
                .append((char) 10);

        if (hasApprovals()) {
            md.append((char) 10).append("## Approvals").append((char) 10);
            if (hasMoveToCodeApproval()) {
                md.append("- **Move to Code:** Approved by ").append(StringUtils.defaultIfBlank(approvedBy, "User"))
                        .append(" on ").append(StringUtils.defaultIfBlank(approvalTimestamp, "N/A")).append((char) 10);
            }
            if (hasFinalAcceptance()) {
                md.append("- **Final Acceptance:** Accepted by ")
                        .append(StringUtils.defaultIfBlank(completedBy, "User")).append(" on ")
                        .append(StringUtils.defaultIfBlank(completedOn, "N/A")).append((char) 10);
            }
        }

        md.append((char) 10).append("## Requirements").append((char) 10);
        for (String req : requirements) {
            md.append("- ").append(req).append((char) 10);
        }
        md.append((char) 10).append("## Technical Steps").append((char) 10).append(formatStepsMarkdown());

        if (usageSummary.hasUsage()) {
            appendUsageMarkdown(md);
        }

        if (includeHandoffLog && !handoffLog.isEmpty()) {
            md.append((char) 10).append("## Handoff Log").append((char) 10).append("| Timestamp | User | Message |")
                    .append((char) 10).append("| :--- | :--- | :--- |").append((char) 10);
            for (LogEntry entry : handoffLog) {
                md.append("| ").append(escapeMarkdownTableCell(entry.formattedTimestamp())).append(" | ")
                        .append(escapeMarkdownTableCell(entry.user())).append(" | ")
                        .append(escapeMarkdownTableCell(entry.message())).append(" |").append((char) 10);
            }
        }
        return md.toString();
    }

    private void appendUsageMarkdown(StringBuilder md) {
        int cacheHitPct = usageSummary.cacheHitPercent();
        int uncachedInPct = 0;
        if (usageSummary.promptTokens() > 0) {
            uncachedInPct = Math.max(0, 100 - cacheHitPct);
        }
        long outAndThinkTokens = usageSummary.outputAndThinkingTokens();
        int outputPct = TokenUtils.computeCacheHitPercent(usageSummary.outputTokens(), outAndThinkTokens);
        int thinkingPct = 0;
        if (outAndThinkTokens > 0) {
            thinkingPct = Math.max(0, 100 - outputPct);
        }

        md.append((char) 10).append("## Token Usage & Cost").append((char) 10).append((char) 10)
                .append("### Cost by Token Category").append((char) 10);
        md.append("| Category | Tokens | Share / Cache Hit | Cost (USD) |").append((char) 10);
        md.append("| :--- | ---: | ---: | ---: |").append((char) 10);
        md.append("| Uncached Input | ").append(TokenUtils.formatTokenCount(usageSummary.uncachedInputTokens()))
                .append(" | ").append(uncachedInPct).append("% | ")
                .append(TokenUtils.formatCostUsd(usageSummary.uncachedInputCostUsd())).append(" |").append((char) 10);
        md.append("| Cached Input | ").append(TokenUtils.formatTokenCount(usageSummary.cachedInputTokens()))
                .append(" | ").append(cacheHitPct).append("% hit | ")
                .append(TokenUtils.formatCostUsd(usageSummary.cachedInputCostUsd())).append(" |").append((char) 10);
        md.append("| **Input Subtotal** | **").append(TokenUtils.formatTokenCount(usageSummary.promptTokens()))
                .append("** | **100% in** | **").append(TokenUtils.formatCostUsd(usageSummary.inputCostUsd()))
                .append("** |").append((char) 10);
        md.append("| Output | ").append(TokenUtils.formatTokenCount(usageSummary.outputTokens())).append(" | ")
                .append(outputPct).append("% | ").append(TokenUtils.formatCostUsd(usageSummary.outputCostUsd()))
                .append(" |").append((char) 10);
        md.append("| Thinking | ").append(TokenUtils.formatTokenCount(usageSummary.thoughtsTokens())).append(" | ")
                .append(thinkingPct).append("% | ").append(TokenUtils.formatCostUsd(usageSummary.thoughtsCostUsd()))
                .append(" |").append((char) 10);
        md.append("| **Output + Thinking Subtotal** | **").append(TokenUtils.formatTokenCount(outAndThinkTokens))
                .append("** | **100% out** | **")
                .append(TokenUtils.formatCostUsd(usageSummary.outputAndThinkingCostUsd())).append("** |")
                .append((char) 10);
        md.append("| **Total (").append(usageSummary.modelCalls()).append(" calls)** | **")
                .append(TokenUtils.formatTokenCount(usageSummary.totalTokens())).append(" (")
                .append(usageSummary.totalTokens()).append(")** | — | **")
                .append(TokenUtils.formatCostUsd(usageSummary.estimatedCostUsd())).append("** |").append((char) 10);

        md.append((char) 10).append("### Cost by Workflow Phase").append((char) 10);
        md.append("| Phase | Calls | Tokens | Cost (USD) |").append((char) 10);
        md.append("| :--- | ---: | ---: | ---: |").append((char) 10);
        appendPhaseUsageRow(md, "Plan", usageSummary.planPhaseUsage());
        appendPhaseUsageRow(md, "Code", usageSummary.codePhaseUsage());
        appendPhaseUsageRow(md, "Review", usageSummary.reviewPhaseUsage());
        md.append("| **Total** | **").append(usageSummary.modelCalls()).append("** | **")
                .append(TokenUtils.formatTokenCount(usageSummary.totalTokens())).append("** | **")
                .append(TokenUtils.formatCostUsd(usageSummary.estimatedCostUsd())).append("** |").append((char) 10);
    }

    private static void appendPhaseUsageRow(StringBuilder md, String phaseLabel, PhaseUsage phaseUsage) {
        md.append("| ").append(phaseLabel).append(" | ").append(phaseUsage.modelCalls()).append(" | ")
                .append(TokenUtils.formatTokenCount(phaseUsage.totalTokens())).append(" | ")
                .append(TokenUtils.formatCostUsd(phaseUsage.estimatedCostUsd())).append(" |").append((char) 10);
    }

    private static String formatFrontmatterCost(double costUsd) {
        return String.format(Locale.US, "%.4f", costUsd);
    }

    public String toArchivedMarkdown(String id, String projectName, String projectPath) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("id", id);
        frontmatter.put("project", projectName);
        frontmatter.put("projectPath", projectPath);
        frontmatter.put("title", title);
        frontmatter.put("userApproved", userApproved);
        frontmatter.put("approvedBy", approvedBy);
        frontmatter.put("approvalTimestamp", approvalTimestamp);
        frontmatter.put("completedBy", completedBy);
        frontmatter.put("completedOn", completedOn);
        frontmatter.put("totalTokens", usageSummary.totalTokens());
        frontmatter.put("cachedTokens", usageSummary.cachedInputTokens());
        frontmatter.put("cacheHitPercent", usageSummary.cacheHitPercent());
        frontmatter.put("uncachedInputCostUsd", formatFrontmatterCost(usageSummary.uncachedInputCostUsd()));
        frontmatter.put("cachedInputCostUsd", formatFrontmatterCost(usageSummary.cachedInputCostUsd()));
        frontmatter.put("outputCostUsd", formatFrontmatterCost(usageSummary.outputCostUsd()));
        frontmatter.put("thoughtsCostUsd", formatFrontmatterCost(usageSummary.thoughtsCostUsd()));
        frontmatter.put("planCostUsd", formatFrontmatterCost(usageSummary.planPhaseUsage().estimatedCostUsd()));
        frontmatter.put("codeCostUsd", formatFrontmatterCost(usageSummary.codePhaseUsage().estimatedCostUsd()));
        frontmatter.put("reviewCostUsd", formatFrontmatterCost(usageSummary.reviewPhaseUsage().estimatedCostUsd()));
        frontmatter.put("estimatedCostUsd", formatFrontmatterCost(usageSummary.estimatedCostUsd()));
        StringBuilder sb = new StringBuilder(FrontmatterUtils.formatFrontmatter(frontmatter));
        sb.append(toMarkdown(true));
        appendChangesMarkdown(sb);
        return sb.toString();
    }

    public String formatChangesText() {
        if (fileChanges.isEmpty()) {
            return NO_FILES_CHANGED;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fileChanges.size(); i++) {
            FileChange change = fileChanges.get(i);
            if (i > 0) {
                sb.append((char) 10);
            }
            sb.append(change.changeType().getLabel()).append(' ').append(change.path());
        }
        return sb.toString();
    }

    public void appendChangesMarkdown(StringBuilder sb) {
        String changes = formatChangesText();
        sb.append((char) 10).append("## Changes").append((char) 10);
        if (NO_FILES_CHANGED.equals(changes)) {
            sb.append(NO_FILES_CHANGED).append((char) 10);
        } else {
            sb.append(CODE_FENCE).append("text").append((char) 10).append(changes).append((char) 10).append(CODE_FENCE)
                    .append((char) 10);
        }
    }

    public String formatStepsMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < technicalSteps.size(); i++) {
            TechStep step = technicalSteps.get(i);
            if (step.completed()) {
                sb.append("- [x] ").append(i).append(". ~~").append(step.description()).append("~~").append((char) 10);
            } else {
                sb.append("- [ ] ").append(i).append(". ").append(step.description()).append((char) 10);
            }
        }
        return sb.toString();
    }

    public String formatPromptContextMarkdown() {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(title)) {
            sb.append("**Title:** ").append(title).append((char) 10);
        }
        if (StringUtils.isNotBlank(goal)) {
            sb.append("**Goal:** ").append(goal).append((char) 10);
        }
        if (!requirements.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append((char) 10);
            }
            sb.append("### Requirements").append((char) 10);
            for (String req : requirements) {
                sb.append("- ").append(req).append((char) 10);
            }
        }
        if (!technicalSteps.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append((char) 10);
            }
            sb.append("### Technical Steps").append((char) 10).append(formatStepsMarkdown());
        }
        if (!fileChanges.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append((char) 10);
            }
            sb.append("### Changes").append((char) 10).append(formatChangesText()).append((char) 10);
        }
        if (!handoffLog.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append((char) 10);
            }
            sb.append("### Handoff Log").append((char) 10);
            for (LogEntry entry : handoffLog) {
                sb.append("- [").append(entry.formattedTimestamp()).append("] ")
                        .append(StringUtils.defaultString(entry.user())).append(": ")
                        .append(StringUtils.defaultString(entry.message())).append((char) 10);
            }
        }
        return sb.toString();
    }

    private static String escapeMarkdownTableCell(String value) {
        return StringUtils.defaultString(value).replace("|", (char) 92 + "|").replace((char) 13 + "" + (char) 10, " ")
                .replace((char) 10, ' ').replace((char) 13, ' ');
    }
}
