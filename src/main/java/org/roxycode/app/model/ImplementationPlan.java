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
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;

public record ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
        boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
        List<LogEntry> handoffLog, List<FileChange> fileChanges) {

    public static final String NO_FILES_CHANGED = "No files changed";
    public static final String STEP_COMPILE = "Code compiles successfully";
    public static final String STEP_UNIT_TESTS = "Unit tests pass";
    public static final String STEP_CODE_REVIEW = "Automated code review passes";
    private static final String CODE_FENCE = "```";

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

    public ImplementationPlan {
        requirements = List.copyOf(Objects.requireNonNullElse(requirements, List.of()));
        technicalSteps = List.copyOf(Objects.requireNonNullElse(technicalSteps, List.of()));
        handoffLog = List.copyOf(Objects.requireNonNullElse(handoffLog, List.of()));
        fileChanges = List.copyOf(Objects.requireNonNullElse(fileChanges, List.of()));
    }

    public ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
            boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
            List<LogEntry> handoffLog) {
        this(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp, approvedBy, completedBy,
                completedOn, handoffLog, List.of());
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

        return withTechnicalSteps(steps).withHandoffLog(preservedLog).withFileChanges(preservedChanges);
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
                completedBy, completedOn, handoffLog, fileChanges);
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
                completedOn, handoffLog, fileChanges);
    }

    public ImplementationPlan withCompletion(String user, String timestamp) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, user, timestamp, handoffLog, fileChanges);
    }

    public ImplementationPlan withHandoffLog(List<LogEntry> newLog) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, newLog, fileChanges);
    }

    public ImplementationPlan withAddedHandoffLogEntry(String timestamp, String user, String message) {
        List<LogEntry> updatedLog = new ArrayList<>(handoffLog);
        updatedLog.add(new LogEntry(timestamp, user, message));
        return withHandoffLog(updatedLog);
    }

    public ImplementationPlan withFileChanges(List<FileChange> newChanges) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, handoffLog, newChanges);
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
                .append("\n\n**Goal:** ").append(StringUtils.defaultString(goal)).append("\n");

        if (hasApprovals()) {
            md.append("\n## Approvals\n");
            if (hasMoveToCodeApproval()) {
                md.append("- **Move to Code:** Approved by ").append(StringUtils.defaultIfBlank(approvedBy, "User"))
                        .append(" on ").append(StringUtils.defaultIfBlank(approvalTimestamp, "N/A")).append("\n");
            }
            if (hasFinalAcceptance()) {
                md.append("- **Final Acceptance:** Accepted by ")
                        .append(StringUtils.defaultIfBlank(completedBy, "User")).append(" on ")
                        .append(StringUtils.defaultIfBlank(completedOn, "N/A")).append("\n");
            }
        }

        md.append("\n## Requirements\n");
        for (String req : requirements) {
            md.append("- ").append(req).append("\n");
        }
        md.append("\n## Technical Steps\n").append(formatStepsMarkdown());

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
                sb.append("- [x] ").append(i).append(". ~~").append(step.description()).append("~~\n");
            } else {
                sb.append("- [ ] ").append(i).append(". ").append(step.description()).append("\n");
            }
        }
        return sb.toString();
    }

    private static String escapeMarkdownTableCell(String value) {
        return StringUtils.defaultString(value).replace("|", (char) 92 + "|").replace((char) 13 + "" + (char) 10, " ")
                .replace((char) 10, ' ').replace((char) 13, ' ');
    }
}
