package org.roxycode.core.tools.service.plans;

import java.util.ArrayList;
import java.util.List;

public class Plan {
    private String name;
    private String goal;
    private List<String> proposedChanges = new ArrayList<>();
    private List<String> implementationSteps = new ArrayList<>();
    private List<String> implementationProgress = new ArrayList<>();
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

    public List<String> getImplementationSteps() {
        return implementationSteps;
    }

    public void setImplementationSteps(List<String> implementationSteps) {
        this.implementationSteps = implementationSteps;
    }

    public List<String> getImplementationProgress() {
        return implementationProgress;
    }

    public void setImplementationProgress(List<String> implementationProgress) {
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
