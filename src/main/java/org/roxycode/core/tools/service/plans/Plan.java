package org.roxycode.core.tools.service.plans;

import java.util.ArrayList;
import java.util.List;

public class Plan {
    public record TaskItem(String text, boolean completed) {
        @Override
        public String toString() {
            return (completed ? "[x] " : "[ ] ") + text;
        }
    }

    private String name;
    private String goal;
    private List<String> proposedChanges = new ArrayList<>();
    private List<TaskItem> implementationSteps = new ArrayList<>();
    private List<TaskItem> implementationProgress = new ArrayList<>();
    private String agentContext;
    private PlanStatus status;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public List<String> getProposedChanges() {
        return proposedChanges;
    }

    public void setProposedChanges(List<String> proposedChanges) {
        this.proposedChanges = proposedChanges;
    }

    public List<TaskItem> getImplementationSteps() {
        return implementationSteps;
    }

    public void setImplementationSteps(List<TaskItem> implementationSteps) {
        this.implementationSteps = implementationSteps;
    }

    public List<TaskItem> getImplementationProgress() {
        return implementationProgress;
    }

    public void setImplementationProgress(List<TaskItem> implementationProgress) {
        this.implementationProgress = implementationProgress;
    }

    public PlanStatus getStatus() {
        return status;
    }

    /**
     * Helper for JS bridge to get status as a simple String.
     */
    public String getStatusString() {
        return status != null ? status.name() : null;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }
    public String getAgentContext() {
        return agentContext;
    }

    public void setAgentContext(String agentContext) {
        this.agentContext = agentContext;
    }
}
