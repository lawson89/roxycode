package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
import org.roxycode.core.RoxyMode;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.ScriptService;
import org.roxycode.core.tools.LLMDoc;

/**
 * Service for determining current mode and changing modes.
 */
@ScriptService("modeService")
@Singleton
public class ModeService {

    private final RoxyProjectService projectService;

    public ModeService(RoxyProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * Returns the current mode of the project.
     *
     * @return The current RoxyMode.
     */
    @LLMDoc("Returns the current mode of the project")
    public RoxyMode getCurrentMode() {
        return this.projectService.getCurrentMode();
    }

    /**
     * Sets the current mode of the project.
     *
     * @param mode The new RoxyMode to set.
     */
    @LLMDoc("Sets the current mode of the project")
    public void setCurrentMode(RoxyMode mode) {
        this.projectService.setCurrentMode(mode);
    }
}
