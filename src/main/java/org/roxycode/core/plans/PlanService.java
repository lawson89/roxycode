package org.roxycode.core.plans;

import jakarta.inject.Provider;
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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ScriptService("planService")
@Singleton
public class PlanService {
    private static final Logger LOG = LoggerFactory.getLogger(PlanService.class);
    private static final String PLANS_DIR = "plans";

    private final Provider<RoxyProjectService> projectServiceProvider;

    public PlanService(Provider<RoxyProjectService> projectServiceProvider) {
        this.projectServiceProvider = projectServiceProvider;
    }


    @LLMDoc("Returns the name of the plan currently being worked on")
    public String getCurrentPlan() {
        return projectServiceProvider.get().getCurrentPlan();
    }

    @LLMDoc("Sets the name of the plan currently being worked on")
    public void setCurrentPlan(String currentPlan) {
        projectServiceProvider.get().setCurrentPlan(currentPlan);
    }

    private void ensureDirectories() {
        Path rootPlans = projectServiceProvider.get().getRoxyWorkingDir().resolve(PLANS_DIR);
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

    @LLMDoc("Creates a new plan with the given details")
    public void createPlan(String name, String goal, List<String> proposedChanges, List<String> steps) throws IOException {
        Plan plan = new Plan();
        plan.setName(name);
        plan.setGoal(goal);
        plan.setProposedChanges(proposedChanges);
        plan.setImplementationSteps(steps);
        plan.setStatus(PlanStatus.AVAILABLE);
        ensureDirectories();
        savePlan(plan);
    }

    @LLMDoc("Updates the goal of an existing plan")
    public void updateGoal(String name, String goal) throws IOException {
        Plan plan = loadPlan(name);
        plan.setGoal(goal);
        savePlan(plan);
    }

    @LLMDoc("Updates the proposed changes of an existing plan")
    public void updateProposedChanges(String name, List<String> changes) throws IOException {
        Plan plan = loadPlan(name);
        plan.setProposedChanges(changes);
        savePlan(plan);
    }

    @LLMDoc("Updates the implementation steps of an existing plan")
    public void updateImplementationSteps(String name, List<String> steps) throws IOException {
        Plan plan = loadPlan(name);
        plan.setImplementationSteps(steps);
        savePlan(plan);
    }

    @LLMDoc("Updates the implementation progress of an existing plan")
    public void updateImplementationProgress(String name, List<String> progress) throws IOException {
        Plan plan = loadPlan(name);
        plan.setImplementationProgress(progress);
        savePlan(plan);
    }

    @LLMDoc("Moves a plan to a new status (AVAILABLE, WORKING, COMPLETE)")
    public void movePlan(String name, PlanStatus newStatus) throws IOException {
        Plan plan = loadPlan(name);
        Path oldPath = getPlanPath(plan);
        plan.setStatus(newStatus);
        Path newPath = getPlanPath(plan);
        Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
    }

    @LLMDoc("Deletes a plan by name")
    public void deletePlan(String name) throws IOException {
        Plan plan = loadPlan(name);
        Files.deleteIfExists(getPlanPath(plan));
    }

    @LLMDoc("Lists plans with the specified status")
    public List<String> listPlans(PlanStatus status) throws IOException {
        Path statusDir = projectServiceProvider.get().getRoxyWorkingDir().resolve(PLANS_DIR).resolve(status.getDirName());
        try (Stream<Path> stream = Files.list(statusDir)) {
            ensureDirectories();
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().replace(".md", ""))
                    .collect(Collectors.toList());
        }
    }

    public Plan loadPlan(String name) throws IOException {
        for (PlanStatus status : PlanStatus.values()) {
            Path path = projectServiceProvider.get().getRoxyWorkingDir().resolve(PLANS_DIR).resolve(status.getDirName()).resolve(name + ".md");
            if (Files.exists(path)) {
                Plan plan = parseMarkdown(Files.readString(path));
                plan.setName(name);
                plan.setStatus(status);
                return plan;
            }
        }
        throw new IOException("Plan not found: " + name);
    }

    private void savePlan(Plan plan) throws IOException {
        String markdown = generateMarkdown(plan);
        Path path = getPlanPath(plan);
        Files.writeString(path, markdown);
    }

    private Path getPlanPath(Plan plan) {
        return projectServiceProvider.get().getRoxyWorkingDir().resolve(PLANS_DIR).resolve(plan.getStatus().getDirName()).resolve(plan.getName() + ".md");
    }

    private String generateMarkdown(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Goal\n").append(plan.getGoal()).append("\n\n");
        sb.append("# Proposed Changes\n");
        for (String change : plan.getProposedChanges()) {
            sb.append("- ").append(change).append("\n");
        }
        sb.append("\n");
        sb.append("# Implementation Steps\n");
        for (String step : plan.getImplementationSteps()) {
            if (step.startsWith("- [")) {
                sb.append(step).append("\n");
            } else {
                sb.append("- [ ] ").append(step).append("\n");
            }
        }
        sb.append("\n");
        sb.append("# Implementation Progress\n");
        for (String progress : plan.getImplementationProgress()) {
            if (progress.startsWith("- [")) {
                sb.append(progress).append("\n");
            } else {
                sb.append("- [x] ").append(progress).append("\n");
            }
        }
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
                        .map(l -> l.replaceFirst("^[-*]\\s*", ""))
                        .collect(Collectors.toList()));
            } else if ("Implementation Steps".equalsIgnoreCase(title)) {
                plan.setImplementationSteps(bodyLines);
            } else if ("Implementation Progress".equalsIgnoreCase(title)) {
                plan.setImplementationProgress(bodyLines);
            }
        }
        return plan;
    }
}
