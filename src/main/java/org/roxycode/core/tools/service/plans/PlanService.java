package org.roxycode.core.tools.service.plans;

import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ScriptService("planService")
@Singleton
@LLMDoc("Service for managing development plans. Enforces transitions: Available <-> In_Progress -> Complete.")
public class PlanService {
    private static final Logger LOG = LoggerFactory.getLogger(PlanService.class);
    private static final String PLANS_DIR = "plans";

    private final RoxyProjectService roxyProjectService;

    public PlanService(RoxyProjectService roxyProjectService) {
        this.roxyProjectService = roxyProjectService;
    }

    @LLMDoc("Returns the name of the plan currently being worked on")
    public String getCurrentPlan() {
        return roxyProjectService.getCurrentPlan();
    }

    @LLMDoc("Sets the name of the plan currently being worked on")
    public void setCurrentPlan(String currentPlan) {
        roxyProjectService.setCurrentPlan(currentPlan);
    }

    @LLMDoc("Returns the markdown content of the current plan")
    public String getCurrentPlanMarkdown() throws IOException {
        String currentPlanName = getCurrentPlan();
        if (currentPlanName == null || currentPlanName.isBlank()) {
            return null;
        }
        Path path = roxyProjectService.getRoxyWorkingDir().resolve(PLANS_DIR).resolve(PlanStatus.IN_PROGRESS.getDirName()).resolve(currentPlanName + ".md");
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return null;
    }


    /**
     * Ensures all 3 status directories exist.
     */
    private void ensureDirectories() {
        Path rootPlans = roxyProjectService.getRoxyWorkingDir().resolve(PLANS_DIR);
        try {
            if (!Files.exists(rootPlans)) {
                Files.createDirectories(rootPlans);
            }
            for (PlanStatus status : PlanStatus.values()) {
                Path statusDir = rootPlans.resolve(status.getDirName());
                if (!Files.exists(statusDir)) {
                    Files.createDirectories(statusDir);
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to create plans directories", e);
        }
    }

    @LLMDoc("Creates a new plan. Plans are always created in 'available' status.")
    public void createPlan(String name, String goal, List<String> proposedChanges, List<String> steps) throws IOException {
        createPlan(name, goal, proposedChanges, steps, null);
    }

    @LLMDoc("Creates a new plan with agent context. Plans are always created in 'available' status.")
    public void createPlan(String name, String goal, List<String> proposedChanges, List<String> steps, String agentContext) throws IOException {
        if (planExists(name)) {
            throw new IOException("A plan with name '" + name + "' already exists.");
        }

        Plan plan = new Plan();
        plan.setName(name);
        plan.setGoal(goal);
        plan.setProposedChanges(proposedChanges != null ? proposedChanges : Collections.emptyList());
        plan.setImplementationSteps(steps != null ? steps : Collections.emptyList());
        plan.setAgentContext(agentContext);
        // Enforce creation rule: Always Available
        plan.setStatus(PlanStatus.AVAILABLE);

        ensureDirectories();
        savePlan(plan);
    }

    @LLMDoc("Updates the goal of an existing plan. Only allowed for Available or In_Progress plans.")
    public void updateGoal(String name, String goal) throws IOException {
        Plan plan = loadPlan(name);
        checkModifiable(plan);
        plan.setGoal(goal);
        savePlan(plan);
    }

    @LLMDoc("Updates the proposed changes of an existing plan. Only allowed for Available or In_Progress plans.")
    public void updateProposedChanges(String name, List<String> changes) throws IOException {
        Plan plan = loadPlan(name);
        checkModifiable(plan);
        plan.setProposedChanges(changes);
        savePlan(plan);
    }

    @LLMDoc("Updates the implementation steps of an existing plan. Only allowed for Available or In_Progress plans.")
    public void updateImplementationSteps(String name, List<String> steps) throws IOException {
        Plan plan = loadPlan(name);
        checkModifiable(plan);
        plan.setImplementationSteps(steps);
        savePlan(plan);
    }

    @LLMDoc("Updates the implementation progress of an existing plan. Only allowed for Available or In_Progress plans.")
    public void updateImplementationProgress(String name, List<String> progress) throws IOException {
        Plan plan = loadPlan(name);
        checkModifiable(plan);
        plan.setImplementationProgress(progress);
        savePlan(plan);
    }

    @LLMDoc("Updates the Agent context of an existing plan. Only allowed for Available or In_Progress plans.")
    public void updateAgentContext(String name, String context) throws IOException {
        Plan plan = loadPlan(name);
        checkModifiable(plan);
        plan.setAgentContext(context);
        savePlan(plan);
    }
    @LLMDoc("Moves a plan to a new status (available, in_progress, complete). Enforces transition rules.")
    public void movePlan(String name, String targetStatusStr) throws IOException {
        Plan plan = loadPlan(name);
        PlanStatus currentStatus = plan.getStatus();
        PlanStatus targetStatus = PlanStatus.fromString(targetStatusStr);

        validateTransition(currentStatus, targetStatus);

        Path oldPath = getPlanPath(plan);
        plan.setStatus(targetStatus);
        Path newPath = getPlanPath(plan);

        // Ensure target directory exists before moving
        if (!Files.exists(newPath.getParent())) {
            Files.createDirectories(newPath.getParent());
        }

        Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);

        // Update the internal content to reflect status if needed,
        // though currently status is determined by folder location.
        // We save to ensure content is synced.
        savePlan(plan);
    }

    @LLMDoc("Deletes a plan. Only 'available' plans can be deleted.")
    public void deletePlan(String name) throws IOException {
        Plan plan = loadPlan(name);
        if (plan.getStatus() != PlanStatus.AVAILABLE) {
            throw new IOException("Cannot delete plan '" + name + "'. Only plans in 'available' can be deleted.");
        }
        Files.deleteIfExists(getPlanPath(plan));
    }

    // --- Listing Methods ---

    @LLMDoc("Lists available plans")
    public List<String> listAvailablePlans() throws IOException {
        return listPlans(PlanStatus.AVAILABLE);
    }

    @LLMDoc("Lists in_progress plans")
    public List<String> listInProgressPlans() throws IOException {
        return listPlans(PlanStatus.IN_PROGRESS);
    }

    @LLMDoc("Lists complete plans")
    public List<String> listCompletePlans() throws IOException {
        return listPlans(PlanStatus.COMPLETE);
    }


    @LLMDoc("Returns the raw markdown content of a plan")
    public String getPlanMarkdown(String name) throws IOException {
        PlanStatus[] searchOrder = {PlanStatus.IN_PROGRESS, PlanStatus.AVAILABLE, PlanStatus.COMPLETE};

        for (PlanStatus status : searchOrder) {
            Path path = roxyProjectService.getRoxyWorkingDir().resolve(PLANS_DIR).resolve(status.getDirName()).resolve(name + ".md");
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("Plan not found: " + name);
    }
    // --- Helper Logic ---

    private void checkModifiable(Plan plan) throws IOException {
        if (plan.getStatus() == PlanStatus.COMPLETE) {
            throw new IOException("Cannot modify plan '" + plan.getName() + "' because it is marked as COMPLETE.");
        }
    }

    private void validateTransition(PlanStatus current, PlanStatus target) throws IOException {
        if (current == target) return; // No change

        boolean valid = false;

        // Rule: Available -> In Progress
        if (current == PlanStatus.AVAILABLE && target == PlanStatus.IN_PROGRESS) valid = true;

            // Rule: In Progress -> Complete
        else if (current == PlanStatus.IN_PROGRESS && target == PlanStatus.COMPLETE) valid = true;

            // Rule: In Progress -> Available (Rollback)
        else if (current == PlanStatus.IN_PROGRESS && target == PlanStatus.AVAILABLE) valid = true;

        if (!valid) {
            throw new IOException("Invalid status transition from " + current + " to " + target +
                                  ". Allowed: Available <-> In_Progress -> Complete.");
        }
    }

    protected List<String> listPlans(PlanStatus planStatus) throws IOException {
        Path statusDir = roxyProjectService.getRoxyWorkingDir().resolve(PLANS_DIR).resolve(planStatus.getDirName());
        ensureDirectories();
        try (Stream<Path> stream = Files.list(statusDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().replace(".md", ""))
                    .collect(Collectors.toList());
        }
    }

    public Plan loadPlan(String name) throws IOException {
        // Efficient search order: check active/likely locations first
        PlanStatus[] searchOrder = {PlanStatus.IN_PROGRESS, PlanStatus.AVAILABLE, PlanStatus.COMPLETE};

        for (PlanStatus status : searchOrder) {
            Path path = roxyProjectService.getRoxyWorkingDir().resolve(PLANS_DIR).resolve(status.getDirName()).resolve(name + ".md");
            if (Files.exists(path)) {
                Plan plan = parseMarkdown(Files.readString(path));
                plan.setName(name);
                plan.setStatus(status); // Set status based on folder found
                return plan;
            }
        }
        throw new IOException("Plan not found: " + name);
    }

    public boolean planExists(String name) {
        try {
            loadPlan(name);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void savePlan(Plan plan) throws IOException {
        String markdown = planToMarkdown(plan);
        Path path = getPlanPath(plan);
        Files.writeString(path, markdown);
    }

    private Path getPlanPath(Plan plan) {
        return roxyProjectService.getRoxyWorkingDir()
                .resolve(PLANS_DIR)
                .resolve(plan.getStatus().getDirName())
                .resolve(plan.getName() + ".md");
    }

    private String planToMarkdown(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Goal\n").append(plan.getGoal() == null ? "" : plan.getGoal()).append("\n\n");

        sb.append("# Proposed Changes\n");
        if (plan.getProposedChanges() != null) {
            for (String change : plan.getProposedChanges()) {
                sb.append("- ").append(change).append("\n");
            }
        }
        sb.append("\n");

        sb.append("# Implementation Steps\n");
        if (plan.getImplementationSteps() != null) {
            for (String step : plan.getImplementationSteps()) {
                if (step.startsWith("- [")) {
                    sb.append(step).append("\n");
                } else {
                    sb.append("- [ ] ").append(step).append("\n");
                }
            }
        }
        sb.append("\n");

        sb.append("# Implementation Progress\n");
        if (plan.getImplementationProgress() != null) {
            for (String progress : plan.getImplementationProgress()) {
                if (progress.startsWith("- [")) {
                    sb.append(progress).append("\n");
                } else {
                    sb.append("- [x] ").append(progress).append("\n");
                }
            }
        }
        sb.append("\n");

        sb.append("# Agent Context\n").append(plan.getAgentContext() == null ? "" : plan.getAgentContext()).append("\n");

        return sb.toString();
    }

    private Plan parseMarkdown(String content) {
        Plan plan = new Plan();
        String[] sections = content.split("(?m)^# ");
        for (String section : sections) {
            if (section.trim().isEmpty()) continue;
            String[] lines = section.split("\n");
            String title = lines[0].trim();
            List<String> bodyLines = Arrays.stream(lines).skip(1)
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());

            if ("Goal".equalsIgnoreCase(title)) {
                plan.setGoal(String.join("\n", bodyLines));
            } else if ("Proposed Changes".equalsIgnoreCase(title)) {
                plan.setProposedChanges(bodyLines.stream()
                        .map(l -> l.replaceFirst("^[\\-*]\\s*", ""))
                        .collect(Collectors.toList()));
            } else if ("Implementation Steps".equalsIgnoreCase(title)) {
                plan.setImplementationSteps(bodyLines);
            } else if ("Implementation Progress".equalsIgnoreCase(title)) {
                plan.setImplementationProgress(bodyLines);
            } else if ("Agent Context".equalsIgnoreCase(title)) {
                plan.setAgentContext(String.join("\n", bodyLines));
            }
        }
        return plan;
    }
}
