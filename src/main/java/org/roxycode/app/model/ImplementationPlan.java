package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
        boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
        List<LogEntry> handoffLog) {

    public static final String NO_FILES_CHANGED = "No files changed";
    public static final String STEP_COMPILE = "Code compiles successfully";
    public static final String STEP_UNIT_TESTS = "Unit tests pass";
    public static final String STEP_CODE_REVIEW = "Automated code review passes";
    private static final String CODE_FENCE = "```";

    public record TechStep(String description, boolean completed) {
    }

    public record LogEntry(String timestamp, String user, String message) {
        private static final int TIMESTAMP_LENGTH = 19;

        public String formattedTimestamp() {
            if (timestamp == null)
                return "";
            return timestamp.length() > TIMESTAMP_LENGTH
                    ? timestamp.substring(0, TIMESTAMP_LENGTH).replace('T', ' ')
                    : timestamp;
        }
    }

    public ImplementationPlan {
        requirements = requirements != null ? List.copyOf(requirements) : List.of();
        technicalSteps = technicalSteps != null ? List.copyOf(technicalSteps) : List.of();
        handoffLog = handoffLog != null ? List.copyOf(handoffLog) : List.of();
    }

    public static String normalizeStepDescription(String description) {
        if (description == null) {
            return "";
        }
        String trimmed = description.trim();
        int end = trimmed.length();
        while (end > 0) {
            char c = trimmed.charAt(end - 1);
            if (c == '.' || c == '!' || c == ':' || c == ';' || Character.isWhitespace(c)) {
                end--;
            } else {
                break;
            }
        }
        return trimmed.substring(0, end).toLowerCase(Locale.ROOT);
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
        Set<String> previouslyCompletedSteps = previousPlan != null
                ? previousPlan.collectCompletedNonVerificationSteps()
                : Set.of();
        List<LogEntry> preservedLog = (handoffLog.isEmpty() && previousPlan != null)
                ? previousPlan.handoffLog()
                : handoffLog;

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

        return withTechnicalSteps(steps).withHandoffLog(preservedLog);
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
        return remaining.isEmpty()
                ? "Remaining incomplete steps: none (all steps completed)."
                : "Remaining incomplete steps: [" + String.join(", ", remaining) + "]";
    }

    public ImplementationPlan withTechnicalSteps(List<TechStep> newSteps) {
        return new ImplementationPlan(title, goal, requirements, newSteps, userApproved, approvalTimestamp, approvedBy,
                completedBy, completedOn, handoffLog);
    }

    public ImplementationPlan withApproval(String user, String timestamp) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, true, timestamp, user, completedBy,
                completedOn, handoffLog);
    }

    public ImplementationPlan withCompletion(String user, String timestamp) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, user, timestamp, handoffLog);
    }

    public ImplementationPlan withHandoffLog(List<LogEntry> newLog) {
        return new ImplementationPlan(title, goal, requirements, technicalSteps, userApproved, approvalTimestamp,
                approvedBy, completedBy, completedOn, newLog);
    }

    public ImplementationPlan withAddedHandoffLogEntry(String timestamp, String user, String message) {
        List<LogEntry> updatedLog = new ArrayList<>(handoffLog);
        updatedLog.add(new LogEntry(timestamp, user, message));
        return withHandoffLog(updatedLog);
    }

    public boolean hasMoveToCodeApproval() {
        return userApproved || isNonBlank(approvedBy) || isNonBlank(approvalTimestamp);
    }

    public boolean hasFinalAcceptance() {
        return isNonBlank(completedBy) || isNonBlank(completedOn);
    }

    public boolean hasApprovals() {
        return hasMoveToCodeApproval() || hasFinalAcceptance();
    }

    public String toMarkdown(boolean includeHandoffLog) {
        StringBuilder md = new StringBuilder("# Implementation Plan: ").append(title != null ? title : "")
                .append("\n\n**Goal:** ").append(goal != null ? goal : "").append("\n");

        if (hasApprovals()) {
            md.append("\n## Approvals\n");
            if (hasMoveToCodeApproval()) {
                md.append("- **Move to Code:** Approved by ").append(isNonBlank(approvedBy) ? approvedBy : "User")
                        .append(" on ").append(isNonBlank(approvalTimestamp) ? approvalTimestamp : "N/A").append("\n");
            }
            if (hasFinalAcceptance()) {
                md.append("- **Final Acceptance:** Accepted by ").append(isNonBlank(completedBy) ? completedBy : "User")
                        .append(" on ").append(isNonBlank(completedOn) ? completedOn : "N/A").append("\n");
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

    public String toArchivedMarkdown(String id, String projectName, String projectPath, String changesText) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                ---
                id: "%s"
                project: "%s"
                projectPath: "%s"
                title: "%s"
                userApproved: %s
                approvedBy: "%s"
                approvalTimestamp: "%s"
                completedBy: "%s"
                completedOn: "%s"
                ---

                """.formatted(escapeFrontmatterValue(id), escapeFrontmatterValue(projectName),
                escapeFrontmatterValue(projectPath), escapeFrontmatterValue(title), userApproved,
                escapeFrontmatterValue(approvedBy), escapeFrontmatterValue(approvalTimestamp),
                escapeFrontmatterValue(completedBy), escapeFrontmatterValue(completedOn)));
        sb.append(toMarkdown(true));
        appendChangesMarkdown(sb, changesText);
        return sb.toString();
    }

    public static String formatChangesText(String gitStatus) {
        return (gitStatus == null || gitStatus.isBlank()) ? NO_FILES_CHANGED : gitStatus.stripTrailing();
    }

    public static void appendChangesMarkdown(StringBuilder sb, String gitStatus) {
        String changes = formatChangesText(gitStatus);
        sb.append("\n## Changes\n");
        if (NO_FILES_CHANGED.equals(changes)) {
            sb.append(NO_FILES_CHANGED).append("\n");
        } else {
            sb.append(CODE_FENCE).append("text\n").append(changes).append("\n").append(CODE_FENCE).append("\n");
        }
    }

    public String formatStepsMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < technicalSteps.size(); i++) {
            TechStep step = technicalSteps.get(i);
            sb.append(step.completed() ? "- [x] " : "- [ ] ").append(i).append(". ").append(step.description())
                    .append("\n");
        }
        return sb.toString();
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static String escapeFrontmatterValue(String value) {
        if (value == null)
            return "";
        return value.replace("\"", "\\\"").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private static String escapeMarkdownTableCell(String value) {
        if (value == null)
            return "";
        return value.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
}
