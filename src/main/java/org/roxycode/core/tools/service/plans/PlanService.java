package org.roxycode.core.tools.service.plans;

import org.roxycode.core.RoxyProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
        sb.append("## Implementation Steps\n").append(convertToMarkdownList(steps, "- [ ]")).append("\n\n");
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

    public void updateGoal(String name, String goal) throws IOException {
        updatePlanSection(name, "Goal", goal);
    }

    public void updateProposedChanges(String name, List<String> changes) throws IOException {
        updatePlanSection(name, "Proposed Changes", convertToMarkdownList(changes, "-"));
    }

    public void updateImplementationSteps(String name, List<String> steps) throws IOException {
        updatePlanSection(name, "Implementation Steps", convertToMarkdownList(steps, "- [ ]"));
    }

    public void updateImplementationProgress(String name, List<String> progress) throws IOException {
        updatePlanSection(name, "Implementation Progress", convertToMarkdownList(progress, "- [x]"));
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

        // Transitions: Available <-> In_Progress -> Complete
        if (currentStatus == PlanStatus.AVAILABLE && targetStatus == PlanStatus.COMPLETE) {
            throw new IOException("Cannot move directly from AVAILABLE to COMPLETE. Move to IN_PROGRESS first.");
        }
        if (currentStatus == PlanStatus.COMPLETE) {
            throw new IOException("Cannot move a COMPLETE plan.");
        }

        Path source = getPlanPath(name, currentStatus);
        Path target = getPlanPath(name, targetStatus);
        Files.createDirectories(target.getParent());
        Files.move(source, target);
        
        if (targetStatus == PlanStatus.COMPLETE) {
            if (name.equals(roxyProjectService.getCurrentPlan())) {
                roxyProjectService.setCurrentPlan(null);
            }
        }
    }

    public void deletePlan(String name) throws IOException {
        if (!planExists(name)) return;
        Plan plan = loadPlan(name);
        if (plan.getStatus() != PlanStatus.AVAILABLE) {
            throw new IOException("Only AVAILABLE plans can be deleted.");
        }
        Files.delete(getPlanPath(name, PlanStatus.AVAILABLE));
    }

    public List<String> listAvailablePlans() throws IOException {
        return listPlans(PlanStatus.AVAILABLE);
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
                    .collect(Collectors.joining("\n")).lines().toList(); // Using lines().toList() for compatibility
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
        plan.setImplementationSteps(getLinesInSection(markdown, "Implementation Steps", "- [ ]"));
        plan.setImplementationProgress(getLinesInSection(markdown, "Implementation Progress", "- [x]"));
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
        if (current == null) return null;
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

    private String replaceSection(String markdown, String sectionName, String newContent) {
        String sectionHeader = "## " + sectionName;
        int start = markdown.indexOf(sectionHeader);
        if (start == -1) return markdown;

        int nextSection = markdown.indexOf("## ", start + sectionHeader.length());
        if (nextSection == -1) {
            return markdown.substring(0, start) + sectionHeader + "\n" + (newContent.isEmpty() ? "" : newContent + "\n");
        } else {
            return markdown.substring(0, start) + sectionHeader + "\n" + (newContent.isEmpty() ? "" : newContent + "\n") + markdown.substring(nextSection);
        }
    }
}
