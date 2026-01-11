package org.roxycode.core.tools.service.plans;

import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ScriptService("planService")
@Singleton
public class PlanService {
    private static final Logger logger = LoggerFactory.getLogger(PlanService.class);
    private final RoxyProjectService roxyProjectService;

    public PlanService(RoxyProjectService roxyProjectService) {
        this.roxyProjectService = roxyProjectService;
    }

    public String getCurrentPlan() {
        return roxyProjectService.getCurrentPlan();
    }

    public void setCurrentPlan(String currentPlan) {
        roxyProjectService.setCurrentPlan(currentPlan);
    }

    public void createPlan(String name, String goal, List<String> proposedChanges, List<String> steps) throws IOException {
        createPlan(name, goal, proposedChanges, steps, "");
    }

    public void createPlan(String name, String goal, List<String> proposedChanges, List<String> steps, String agentContext) throws IOException {
        if (planExists(name)) {
            throw new IOException("Plan already exists: " + name);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append("\n\n");
        sb.append("## Goal\n").append(goal != null ? goal : "").append("\n\n");
        sb.append("## Proposed Changes\n").append(convertToMarkdownList(proposedChanges, "-")).append("\n\n");
        
        List<Plan.TaskItem> taskSteps = steps != null ? steps.stream().map(s -> new Plan.TaskItem(s, false)).toList() : new ArrayList<>();
        sb.append("## Implementation Steps\n").append(convertTasksToMarkdown(taskSteps)).append("\n\n");
        
        sb.append("## Implementation Progress\n\n");
        sb.append("## Agent Context\n").append(agentContext != null ? agentContext : "").append("\n");

        Path path = getPlanPath(name, PlanStatus.AVAILABLE);
        Files.createDirectories(path.getParent());
        Files.writeString(path, sb.toString());
    }

    private String convertToMarkdownList(List<String> items, String prefix) {
        if (items == null || items.isEmpty()) return "";
        return items.stream()
                .map(item -> prefix + " " + item)
                .collect(Collectors.joining("\n"));
    }

    private String convertTasksToMarkdown(List<Plan.TaskItem> items) {
        if (items == null || items.isEmpty()) return "";
        return items.stream()
                .map(item -> "- " + item.toString())
                .collect(Collectors.joining("\n"));
    }

    public void updateGoal(String name, String goal) throws IOException {
        updatePlanSection(name, "Goal", goal);
    }

    public void updateProposedChanges(String name, List<String> changes) throws IOException {
        updatePlanSection(name, "Proposed Changes", convertToMarkdownList(changes, "-"));
    }

    public void updateImplementationSteps(String name, List<String> steps) throws IOException {
        List<Plan.TaskItem> taskSteps = steps != null ? steps.stream().map(s -> new Plan.TaskItem(s, false)).toList() : new ArrayList<>();
        updatePlanSection(name, "Implementation Steps", convertTasksToMarkdown(taskSteps));
    }

    public void updateImplementationProgress(String name, List<String> progress) throws IOException {
        List<Plan.TaskItem> taskProgress = progress != null ? progress.stream().map(s -> {
            if (s.startsWith("[ ] ")) return new Plan.TaskItem(s.substring(4), false);
            if (s.startsWith("[x] ") || s.startsWith("[X] ")) return new Plan.TaskItem(s.substring(4), true);
            return new Plan.TaskItem(s, true);
        }).toList() : new ArrayList<>();
        updatePlanSection(name, "Implementation Progress", convertTasksToMarkdown(taskProgress));
    }

    public void addProgressStep(String name, String stepText, boolean completed) throws IOException {
        Plan plan = loadPlan(name);
        List<Plan.TaskItem> progress = new ArrayList<>(plan.getImplementationProgress());
        progress.add(new Plan.TaskItem(stepText, completed));
        updateImplementationProgress(name, progress.stream().map(Plan.TaskItem::toString).toList());
    }

    public void completeStep(String name, String stepText) throws IOException {
        Plan plan = loadPlan(name);
        List<Plan.TaskItem> progress = new ArrayList<>(plan.getImplementationProgress());
        boolean found = false;
        for (int i = 0; i < progress.size(); i++) {
            Plan.TaskItem item = progress.get(i);
            if (item.text().equalsIgnoreCase(stepText)) {
                progress.set(i, new Plan.TaskItem(item.text(), true));
                found = true;
                break;
            }
        }
        if (!found) {
            progress.add(new Plan.TaskItem(stepText, true));
        }
        updateImplementationProgress(name, progress.stream().map(Plan.TaskItem::toString).toList());
    }

    public void updateAgentContext(String name, String context) throws IOException {
        updatePlanSection(name, "Agent Context", context);
    }

    private void updatePlanSection(String name, String sectionName, String newContent) throws IOException {
        Plan plan = loadPlan(name);
        if (plan.getStatus() == PlanStatus.COMPLETE) {
            throw new IOException("Cannot update a completed plan.");
        }

        String markdown = getPlanMarkdown(name);
        String updated = replaceSection(markdown, sectionName, newContent);
        Files.writeString(getPlanPath(name, plan.getStatus()), updated);
    }

    public void movePlan(String name, String targetStatusStr) throws IOException {
        PlanStatus targetStatus = PlanStatus.fromString(targetStatusStr);
        Plan currentPlan = loadPlan(name);
        PlanStatus currentStatus = currentPlan.getStatus();
        if (currentStatus == targetStatus) return;

        // Transitions: Available <-> Planning <-> In_Progress -> Complete
        if ((currentStatus == PlanStatus.AVAILABLE || currentStatus == PlanStatus.PLANNING) && targetStatus == PlanStatus.COMPLETE) {
            throw new IOException("Cannot move directly to COMPLETE from " + currentStatus + ". Move to IN_PROGRESS first.");
        }
        if (currentStatus == PlanStatus.COMPLETE) {
            throw new IOException("Cannot move a COMPLETE plan.");
        }

        Path source = getPlanPath(name, currentStatus);
        Path target = getPlanPath(name, targetStatus);
        Files.createDirectories(target.getParent());
        Files.move(source, target);
    }

    public void deletePlan(String name) throws IOException {
        Plan plan = loadPlan(name);
        if (plan.getStatus() != PlanStatus.AVAILABLE && plan.getStatus() != PlanStatus.PLANNING) {
            throw new IOException("Only AVAILABLE or PLANNING plans can be deleted.");
        }
        Files.delete(getPlanPath(name, plan.getStatus()));
    }

    public Path findPlanPath(String name) {
        PlanStatus status = findPlanStatus(name);
        if (status == null) return null;
        return getPlanPath(name, status);
    }

    public List<String> listAvailablePlans() throws IOException {
        return listPlans(PlanStatus.AVAILABLE);
    }

    public List<String> listPlanningPlans() throws IOException {
        return listPlans(PlanStatus.PLANNING);
    }

    public List<String> listInProgressPlans() throws IOException {
        return listPlans(PlanStatus.IN_PROGRESS);
    }

    public List<String> listCompletePlans() throws IOException {
        return listPlans(PlanStatus.COMPLETE);
    }

    private List<String> listPlans(PlanStatus status) throws IOException {
        Path dir = roxyProjectService.getRoxyWorkingDir().resolve("plans").resolve(status.getDirName());
        if (!Files.exists(dir)) return new ArrayList<>();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString().replace(".md", ""))
                    .collect(Collectors.toList());
        }
    }

    public Plan loadPlan(String name) throws IOException {
        PlanStatus status = findPlanStatus(name);
        if (status == null) throw new IOException("Plan not found: " + name);

        String markdown = Files.readString(getPlanPath(name, status));
        Plan plan = new Plan();
        plan.setName(name);
        plan.setStatus(status);
        plan.setGoal(getSectionContent(markdown, "Goal"));
        plan.setProposedChanges(getLinesInSection(markdown, "Proposed Changes", "-"));
        plan.setImplementationSteps(getTasksInSection(markdown, "Implementation Steps"));
        plan.setImplementationProgress(getTasksInSection(markdown, "Implementation Progress"));
        plan.setAgentContext(getSectionContent(markdown, "Agent Context"));
        return plan;
    }

    public boolean planExists(String name) {
        return findPlanStatus(name) != null;
    }

    private PlanStatus findPlanStatus(String name) {
        for (PlanStatus status : PlanStatus.values()) {
            if (Files.exists(getPlanPath(name, status))) return status;
        }
        return null;
    }

    private Path getPlanPath(String name, PlanStatus status) {
        return roxyProjectService.getRoxyWorkingDir().resolve("plans").resolve(status.getDirName()).resolve(name + ".md");
    }

    public String getPlanMarkdown(String name) throws IOException {
        PlanStatus status = findPlanStatus(name);
        if (status == null) throw new IOException("Plan not found: " + name);
        return Files.readString(getPlanPath(name, status));
    }

    public String getCurrentPlanMarkdown() throws IOException {
        String current = roxyProjectService.getCurrentPlan();
        if (current == null || current.isBlank()) return null;
        return getPlanMarkdown(current);
    }

    private String getSectionContent(String markdown, String sectionName) {
        String sectionHeader = "## " + sectionName;
        int start = markdown.indexOf(sectionHeader);
        if (start == -1) return "";
        start += sectionHeader.length();
        int end = markdown.indexOf("## ", start);
        if (end == -1) return markdown.substring(start).trim();
        return markdown.substring(start, end).trim();
    }

    private List<String> getLinesInSection(String markdown, String sectionName, String prefix) {
        String content = getSectionContent(markdown, sectionName);
        List<String> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                result.add(trimmed.substring(prefix.length()).trim());
            }
        }
        return result;
    }

    private List<Plan.TaskItem> getTasksInSection(String markdown, String sectionName) {
        String content = getSectionContent(markdown, sectionName);
        List<Plan.TaskItem> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- [ ]")) {
                result.add(new Plan.TaskItem(trimmed.substring(5).trim(), false));
            } else if (trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")) {
                result.add(new Plan.TaskItem(trimmed.substring(5).trim(), true));
            } else if (trimmed.startsWith("-")) {
                result.add(new Plan.TaskItem(trimmed.substring(1).trim(), false));
            }
        }
        return result;
    }

    private String replaceSection(String markdown, String sectionName, String newContent) {
        String sectionHeader = "## " + sectionName;
        int start = markdown.indexOf(sectionHeader);
        if (start == -1) return markdown;

        String trimmedContent = newContent != null ? newContent.trim() : "";
        String contentWithNewline = trimmedContent.isEmpty() ? "" : trimmedContent + "\n";

        int nextSection = markdown.indexOf("## ", start + sectionHeader.length());
        if (nextSection == -1) {
            return markdown.substring(0, start) + sectionHeader + "\n" + contentWithNewline;
        } else {
            return markdown.substring(0, start) + sectionHeader + "\n" + contentWithNewline + "\n" + markdown.substring(nextSection);
        }
    }
}
