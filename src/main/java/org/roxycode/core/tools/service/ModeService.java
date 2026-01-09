package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
import org.roxycode.core.RoxyMode;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

/**
 * Service for determining and changing the current operational mode of RoxyCode.
 * RoxyCode operates in two primary modes: PLAN (for architecture and planning)
 * and CODE (for implementation and testing).
 */
@ScriptService("modeService")
@Singleton
@LLMDoc("Service for determining and changing the current operational mode of RoxyCode. RoxyCode operates in two primary modes: PLAN (for architecture and planning) and CODE (for implementation and testing).")
public class ModeService {

    private final RoxyProjectService projectService;

    public ModeService(RoxyProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * Returns the current mode of the project.
     *
     * @return The current RoxyMode as a String (e.g., "PLAN" or "CODE").
     */
    @LLMDoc("Returns the current mode of the project")
    public String getCurrentMode() {
        return this.projectService.getCurrentMode().name();
    }

    /**
     * Sets the current mode of the project to CODE.
     * In CODE mode, the assistant can make changes to the codebase and run tests.
     */
    @LLMDoc("Sets the current mode of the project to CODE")
    public void setCodeMode() {
        this.projectService.setCurrentMode(RoxyMode.CODE);
    }

    /**
     * Sets the current mode of the project to PLAN.
     * In PLAN mode, the assistant focuses on analysis, answering questions, and creating plans.
     */
    @LLMDoc("Sets the current mode of the project to PLAN")
    public void setPlanMode() {
        this.projectService.setCurrentMode(RoxyMode.PLAN);
    }
}
