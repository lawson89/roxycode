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
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.roxycode.app.util.TokenUtils;

/**
 * Represents a structured implementation plan for a feature or task.
 */
public record ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
        boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
        List<LogEntry> handoffLog, List<FileChange> fileChanges, UsageSummary usageSummary, String commitMessage,
        String finalReviewNotes) {

    public static final String NO_FILES_CHANGED = "No files changed";
    public static final String STEP_COMPILE = "Code compiles successfully";
    public static final String STEP_UNIT_TESTS = "Unit tests pass";
    public static final String STEP_CODE_REVIEW = "Automated code review passes";
    private static final String CODE_FENCE = "```";

    /**
     * Represents a single technical implementation step.
     */
    public record TechStep(String description, boolean completed) {
    }

    /**
     * Represents a file change recorded during implementation.
     */
    public record FileChange(String path, FileChangedEvent.ChangeType changeType) {
        public FileChange {
            path = PathUtils.normalizeRelativePath(path);
            changeType = Objects.requireNonNullElse(changeType, FileChangedEvent.ChangeType.MODIFIED);
        }
    }

    /**
     * Represents an entry in the plan's handoff log.
     */
    public record LogEntry(String timestamp, String user, String message) {
        private static final int TIMESTAMP_LENGTH = 19;

        public String formattedTimestamp() {
            return PathUtils.formatTimestampDisplay(timestamp, TIMESTAMP_LENGTH, "");
        }
    }

    /**
     * Concise summary of AI token usage and estimated cost.
     */
    public record UsageSummary(int modelCalls, long promptTokens, long cachedInputTokens, long outputTokens,
            long totalTokens, double estimatedCostUsd) {

        public static final UsageSummary EMPTY = new UsageSummary(0, 0L, 0L, 0L, 0L, 0.0);

        public UsageSummary {
            modelCalls = Math.max(0, modelCalls);
            promptTokens = Math.max(0L, promptTokens);
            cachedInputTokens = Math.max(0L, cachedInputTokens);
            outputTokens = Math.max(0L, outputTokens);
            totalTokens = Math.max(0L, totalTokens);
            estimatedCostUsd = Math.max(0.0, estimatedCostUsd);
        }

        public boolean hasUsage() {
            return modelCalls > 0 || totalTokens > 0L;
        }

        public int cacheHitPercent() {
            return TokenUtils.computeCacheHitPercent(cachedInputTokens, promptTokens);
        }

        public UsageSummary addEvent(TokenUsageEvent event) {
            if (event == null) {
                return this;
            }
            long combinedOutput = Math.max(0L, event.candidatesTokens()) + Math.max(0L, event.thoughtsTokens());
            return new UsageSummary(modelCalls + 1, promptTokens + Math.max(0, event.promptTokens()),
                    cachedInputTokens + Math.max(0, event.cachedInputTokens()), outputTokens + combinedOutput,
                    totalTokens + Math.max(0, event.totalTokens()),
                    estimatedCostUsd + Math.max(0.0, event.estimatedCostUsd()));
        }

        public UsageSummary merge(UsageSummary other) {
            if (other == null || !other.hasUsage()) {
                return this;
            }
            if (!this.hasUsage()) {
                return other;
            }
            return new UsageSummary(modelCalls + other.modelCalls, promptTokens + other.promptTokens,
                    cachedInputTokens + other.cachedInputTokens, outputTokens + other.outputTokens,
                    totalTokens + other.totalTokens, estimatedCostUsd + other.estimatedCostUsd);
        }
    }

    public ImplementationPlan {
        requirements = List.copyOf(Objects.requireNonNullElse(requirements, List.of()));
        technicalSteps = List.copyOf(Objects.requireNonNullElse(technicalSteps, List.of()));
        handoffLog = List.copyOf(Objects.requireNonNullElse(handoffLog, List.of()));
        fileChanges = List.copyOf(Objects.requireNonNullElse(fileChanges, List.of()));
        usageSummary = Objects.requireNonNullElse(usageSummary, UsageSummary.EMPTY);
        commitMessage = StringUtils.trimToNull(commitMessage);
        finalReviewNotes = StringUtils.trimToNull(finalReviewNotes);
    }

    public ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
            boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
            List<LogEntry> handoffLog) {
        this(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp, approvedBy, completedBy,
                completedOn, handoffLog, List.of(), UsageSummary.EMPTY, null, null);
    }

    public long completedStepCount() {
        return technicalSteps.stream().filter(TechStep::completed).count();
    }

    public static String normalizeStepDescription(String description) {
        return StringUtils.stripEnd(StringUtils.trimToEmpty(description), ".!;: \t\r\n").toLowerCase(Locale.ROOT);
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

    Set<String> collectCompletedNonVerificationSteps() {
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
        Set<String> previouslyCompleted = Set.of();
        List<LogEntry> preservedLog = handoffLog;
        List<FileChange> preservedChanges = fileChanges;
        UsageSummary preservedUsage = usageSummary;
        String preservedCommitMsg = commitMessage;
        String preservedReviewNotes = finalReviewNotes;
        if (previousPlan != null) {
            previouslyCompleted = previousPlan.collectCompletedNonVerificationSteps();
            if (preservedLog.isEmpty()) {
                preservedLog = previousPlan.handoffLog();
            }
            if (preservedChanges.isEmpty()) {
                preservedChanges = previousPlan.fileChanges();
            }
            if (previousPlan.usageSummary().hasUsage()) {
                preservedUsage = previousPlan.usageSummary().merge(usageSummary);
            }
            if (preservedCommitMsg == null) {
                preservedCommitMsg = previousPlan.commitMessage();
            }
            if (preservedReviewNotes == null) {
                preservedReviewNotes = previousPlan.finalReviewNotes();
            }
        }

        List<TechStep> steps = new ArrayList<>();
        boolean hasCompile = false;
        boolean hasTests = false;
        boolean hasReview = false;
        for (TechStep s : technicalSteps) {
            String desc = s.description();
            boolean preservedCompleted = s.completed() || (!isMandatoryVerificationStep(desc)
                    && previouslyCompleted.contains(normalizeStepDescription(desc)));
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

        return new ImplementationPlan(title, goal, requirements, steps, userApproved, approvalTimestamp, approvedBy,
                completedBy, completedOn, preservedLog, preservedChanges, preservedUsage, preservedCommitMsg,
                preservedReviewNotes);
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
                completedBy, completedOn, handoffLog, fileChanges, usageSummary, commitMessage, finalReviewNotes);
    }

    public ImplementationPlan withVerificationStepCompleted(String stepDescription, boolean completed) {
        String normalizedTarget = normalizeStepDescription(stepDescription);
        if (normalizedTarget.isEmpty() || technicalSteps.isEmpty()) {
            return this;
        }
        List<TechStep> updatedSteps = new ArrayList<>(technicalSteps.size());
        boolean modified = false;
        for (TechStep step : technicalSteps) {
            if (normalizeStepDescription(step.description()).equals(normalizedTarget)
                    && step.completed() != completed) {
                updatedSteps.add(new TechStep(step.description(), completed));
                modified = true;
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
                completedOn, handoffLog, fileChanges, usageSummary, commitMessage, finalReviewNotes);
    }

    public ImplementationPlan withCompletion(String user, String timestamp) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, user, timestamp, handoffLog, fileChanges, usageSummary, commitMessage, finalReviewNotes);
    }

    public ImplementationPlan withAddedHandoffLogEntry(String timestamp, String user, String message) {
        List<LogEntry> updatedLog = new ArrayList<>(handoffLog);
        updatedLog.add(new LogEntry(timestamp, user, message));
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, updatedLog, fileChanges, usageSummary, commitMessage,
                finalReviewNotes);
    }

    public ImplementationPlan withUsageSummary(UsageSummary newUsage) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, handoffLog, fileChanges, newUsage, commitMessage,
                finalReviewNotes);
    }

    public ImplementationPlan withReviewOutcome(String newFinalReviewNotes, String newCommitMessage) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, handoffLog, fileChanges, usageSummary, newCommitMessage,
                newFinalReviewNotes);
    }

    public ImplementationPlan withRecordedTokenUsage(TokenUsageEvent event) {
        if (event == null) {
            return this;
        }
        return withUsageSummary(usageSummary.addEvent(event));
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
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, handoffLog, updated, usageSummary, commitMessage,
                finalReviewNotes);
    }

    public String toMarkdown(boolean includeHandoffLog) {
        StringBuilder md = new StringBuilder("# Implementation Plan: ").append(StringUtils.defaultString(title))
                .append("\n\n**Goal:** ").append(StringUtils.defaultString(goal)).append('\n');

        boolean hasMoveApproval = userApproved || StringUtils.isNotBlank(approvedBy)
                || StringUtils.isNotBlank(approvalTimestamp);
        boolean hasFinalAcceptance = StringUtils.isNotBlank(completedBy) || StringUtils.isNotBlank(completedOn);
        if (hasMoveApproval || hasFinalAcceptance) {
            md.append("\n## Approvals\n");
            if (hasMoveApproval) {
                md.append("- **Move to Code:** Approved by ").append(StringUtils.defaultIfBlank(approvedBy, "User"))
                        .append(" on ").append(StringUtils.defaultIfBlank(approvalTimestamp, "N/A")).append('\n');
            }
            if (hasFinalAcceptance) {
                md.append("- **Final Acceptance:** Accepted by ")
                        .append(StringUtils.defaultIfBlank(completedBy, "User")).append(" on ")
                        .append(StringUtils.defaultIfBlank(completedOn, "N/A")).append('\n');
            }
        }

        appendBulletList(md, "## Requirements", requirements);
        md.append("\n## Technical Steps\n").append(formatStepsMarkdown());

        if (StringUtils.isNotBlank(finalReviewNotes)) {
            md.append("\n## Final Review Notes\n").append(finalReviewNotes).append('\n');
        }
        if (StringUtils.isNotBlank(commitMessage)) {
            md.append("\n## Proposed Commit Message\n").append(CODE_FENCE).append("text\n").append(commitMessage)
                    .append('\n').append(CODE_FENCE).append('\n');
        }
        if (usageSummary.hasUsage()) {
            appendUsageMarkdown(md);
        }
        if (includeHandoffLog && !handoffLog.isEmpty()) {
            md.append("\n## Handoff Log\n| Timestamp | User | Message |\n| :--- | :--- | :--- |\n");
            for (LogEntry entry : handoffLog) {
                md.append("| ").append(escapeMarkdownTableCell(entry.formattedTimestamp())).append(" | ")
                        .append(escapeMarkdownTableCell(entry.user())).append(" | ")
                        .append(escapeMarkdownTableCell(entry.message())).append(" |\n");
            }
        }
        return md.toString();
    }

    private void appendUsageMarkdown(StringBuilder md) {
        long uncachedInput = Math.max(0L, usageSummary.promptTokens() - usageSummary.cachedInputTokens());
        md.append("\n## Token Usage & Cost\n\n| Metric | Value |\n| :--- | ---: |\n");
        md.append("| Model Calls | ").append(usageSummary.modelCalls()).append(" |\n");
        md.append("| Input Tokens (Uncached / Cached) | ").append(TokenUtils.formatTokenCount(uncachedInput))
                .append(" / ").append(TokenUtils.formatTokenCount(usageSummary.cachedInputTokens())).append(" (")
                .append(usageSummary.cacheHitPercent()).append("% hit) |\n");
        md.append("| Output + Thinking Tokens | ").append(TokenUtils.formatTokenCount(usageSummary.outputTokens()))
                .append(" |\n");
        md.append("| **Total Tokens** | **").append(TokenUtils.formatTokenCount(usageSummary.totalTokens()))
                .append(" (").append(usageSummary.totalTokens()).append(")** |\n");
        md.append("| **Estimated Cost (USD)** | **").append(TokenUtils.formatCostUsd(usageSummary.estimatedCostUsd()))
                .append("** |\n");
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
        frontmatter.put("estimatedCostUsd", String.format(Locale.US, "%.4f", usageSummary.estimatedCostUsd()));
        return FrontmatterUtils.formatFrontmatter(frontmatter) + toFullMarkdown();
    }

    public String toFullMarkdown() {
        StringBuilder sb = new StringBuilder(toMarkdown(true));
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
                sb.append('\n');
            }
            sb.append(change.changeType().getLabel()).append(' ').append(change.path());
        }
        return sb.toString();
    }

    public void appendChangesMarkdown(StringBuilder sb) {
        String changes = formatChangesText();
        sb.append("\n## Changes\n");
        if (NO_FILES_CHANGED.equals(changes)) {
            sb.append(NO_FILES_CHANGED).append('\n');
        } else {
            sb.append(CODE_FENCE).append("text\n").append(changes).append('\n').append(CODE_FENCE).append('\n');
        }
    }

    public String formatStepsMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < technicalSteps.size(); i++) {
            TechStep step = technicalSteps.get(i);
            if (step.completed()) {
                sb.append("- [x] ").append(i).append(". ~~").append(step.description()).append("~~\n");
            } else {
                sb.append("- [ ] ").append(i).append(". ").append(step.description()).append('\n');
            }
        }
        return sb.toString();
    }

    public String formatPromptContextMarkdown() {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(title)) {
            sb.append("**Title:** ").append(title).append('\n');
        }
        if (StringUtils.isNotBlank(goal)) {
            sb.append("**Goal:** ").append(goal).append('\n');
        }
        if (!requirements.isEmpty()) {
            appendBulletList(sb, "### Requirements", requirements);
        }
        if (!technicalSteps.isEmpty()) {
            appendSectionHeader(sb, "### Technical Steps");
            sb.append(formatStepsMarkdown());
        }
        if (!fileChanges.isEmpty()) {
            appendSectionHeader(sb, "### Changes");
            sb.append(formatChangesText()).append('\n');
        }
        if (!handoffLog.isEmpty()) {
            appendSectionHeader(sb, "### Handoff Log");
            for (LogEntry entry : handoffLog) {
                sb.append("- [").append(entry.formattedTimestamp()).append("] ")
                        .append(StringUtils.defaultString(entry.user())).append(": ")
                        .append(StringUtils.defaultString(entry.message())).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendSectionHeader(StringBuilder sb, String heading) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(heading).append('\n');
    }

    private static void appendBulletList(StringBuilder sb, String heading, List<String> items) {
        appendSectionHeader(sb, heading);
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
    }

    private static String escapeMarkdownTableCell(String value) {
        return StringUtils.defaultString(value).replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ')
                .replace('\r', ' ');
    }
}
