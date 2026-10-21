package org.roxycode.app.model;

import java.util.ArrayList;
import java.util.List;

public record ImplementationPlan(
        String title,
        String goal,
        List<String> requirements,
        List<TechStep> technicalSteps,
        boolean userApproved,
        String approvalTimestamp,
        String approvedBy,
        String completedOn,
        List<LogEntry> handoffLog) {
    public record TechStep(String description, boolean completed) {}

        public record LogEntry(String timestamp, String user, String message) {
        private static final int TIMESTAMP_LENGTH = 19;

        public String formattedTimestamp() {
            if (timestamp == null) {
                return "";
            }
            if (timestamp.length() > TIMESTAMP_LENGTH) {
                return timestamp.substring(0, TIMESTAMP_LENGTH).replace('T', ' ');
            }
            return timestamp;
        }
    }

    public ImplementationPlan {
        requirements = requirements != null ? List.copyOf(requirements) : List.of();
        technicalSteps = technicalSteps != null ? List.copyOf(technicalSteps) : List.of();
        handoffLog = handoffLog != null ? List.copyOf(handoffLog) : List.of();
    }

    public ImplementationPlan withTechnicalSteps(List<TechStep> newSteps) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                newSteps,
                userApproved,
                approvalTimestamp,
                approvedBy,
                completedOn,
                handoffLog);
    }

    public ImplementationPlan withApproval(String user, String timestamp) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                technicalSteps,
                true,
                timestamp,
                user,
                completedOn,
                handoffLog);
    }

    public ImplementationPlan withCompletedOn(String timestamp) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                technicalSteps,
                userApproved,
                approvalTimestamp,
                approvedBy,
                timestamp,
                handoffLog);
    }

    public ImplementationPlan withHandoffLog(List<LogEntry> newLog) {
        return new ImplementationPlan(
                title,
                goal,
                requirements,
                technicalSteps,
                userApproved,
                approvalTimestamp,
                approvedBy,
                completedOn,
                newLog);
    }

    public ImplementationPlan withAddedHandoffLogEntry(
            String timestamp, String user, String message) {
        List<LogEntry> updatedLog = new ArrayList<>(handoffLog);
        updatedLog.add(new LogEntry(timestamp, user, message));
        return withHandoffLog(updatedLog);
    }

    /**
     * Formats the implementation plan as Markdown.
     *
     * @param includeHandoffLog whether to include the handoff log table if non-empty
     * @return the Markdown representation of this plan
     */
    public String toMarkdown(boolean includeHandoffLog) {
        StringBuilder md = new StringBuilder();
        md.append(
                """
                # Implementation Plan: %s

                **Goal:** %s

                ## Requirements
                """
                        .formatted(title != null ? title : "", goal != null ? goal : ""));

        for (String req : requirements) {
            md.append("- ").append(req).append("\n");
        }
        md.append("\n## Technical Steps\n");
        int index = 0;
        for (TechStep step : technicalSteps) {
            md.append(step.completed() ? "- [x] " : "- [ ] ")
                    .append(index)
                    .append(". ")
                    .append(step.description())
                    .append("\n");
            index++;
        }

        if (includeHandoffLog && !handoffLog.isEmpty()) {
            md.append(
                    """

                    ## Handoff Log
                    | Timestamp | User | Message |
                    | :--- | :--- | :--- |
                    """);
                        for (LogEntry entry : handoffLog) {
                md.append("| ")
                        .append(escapeMarkdownTableCell(entry.formattedTimestamp()))
                        .append(" | ")
                        .append(escapeMarkdownTableCell(entry.user()))
                        .append(" | ")
                        .append(escapeMarkdownTableCell(entry.message()))
                        .append(" |\n");
            }
        }

        return md.toString();
    }

    private static String escapeMarkdownTableCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
}
