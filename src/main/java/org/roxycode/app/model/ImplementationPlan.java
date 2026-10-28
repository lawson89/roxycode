package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.List;

public record ImplementationPlan(String title, String goal, List<String> requirements, List<TechStep> technicalSteps,
        boolean userApproved, String approvalTimestamp, String approvedBy, String completedBy, String completedOn,
        List<LogEntry> handoffLog) {

    public static final String NO_FILES_CHANGED = "No files changed";
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
