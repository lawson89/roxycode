package org.roxycode.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.tools.ScriptServiceRegistry;
import org.roxycode.core.tools.ToolRegistry;
import org.roxycode.core.tools.service.FileSystemService;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ModeEnforcementTest {

    @TempDir
    Path tempDir;

    private RoxyProjectService roxyProjectService;

    @BeforeEach
    void setUp() {
        Sandbox sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        FileSystemService fileSystemService = new FileSystemService(sandbox);
        SettingsService settingsService = mock(SettingsService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ScriptServiceRegistry scriptServiceRegistry = mock(ScriptServiceRegistry.class);
        
        roxyProjectService = new RoxyProjectService(sandbox, fileSystemService, settingsService, toolRegistry, scriptServiceRegistry);
    }

    @Test
    void testCannotSwitchToCodeModeWithoutPlan() {
        roxyProjectService.setCurrentPlan(null);
        roxyProjectService.setCurrentMode(RoxyMode.PLAN);
        
        assertThrows(IllegalStateException.class, () -> {
            roxyProjectService.setCurrentMode(RoxyMode.CODE);
        });
        
        assertEquals(RoxyMode.PLAN, roxyProjectService.getCurrentMode());
    }

    @Test
    void testCanSwitchToCodeModeWithPlan() {
        roxyProjectService.setCurrentPlan("some-plan");
        roxyProjectService.setCurrentMode(RoxyMode.CODE);
        
        assertEquals(RoxyMode.CODE, roxyProjectService.getCurrentMode());
    }

    @Test
    void testRevertsToPlanModeWhenPlanIsCleared() {
        roxyProjectService.setCurrentPlan("some-plan");
        roxyProjectService.setCurrentMode(RoxyMode.CODE);
        assertEquals(RoxyMode.CODE, roxyProjectService.getCurrentMode());
        
        roxyProjectService.setCurrentPlan(null);
        assertEquals(RoxyMode.PLAN, roxyProjectService.getCurrentMode());
    }

    @Test
    void testRevertsToPlanModeWhenPlanIsEmptyString() {
        roxyProjectService.setCurrentPlan("some-plan");
        roxyProjectService.setCurrentMode(RoxyMode.CODE);
        
        roxyProjectService.setCurrentPlan("");
        assertEquals(RoxyMode.PLAN, roxyProjectService.getCurrentMode());
    }
}
