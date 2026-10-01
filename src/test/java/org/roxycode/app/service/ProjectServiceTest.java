package org.roxycode.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.model.AppSettings;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testProjectPersistence() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        
        ProjectService projectService = new ProjectService(settingsService);
        Path projectPath = tempDir.resolve("my-project");
        
        projectService.setCurrentProjectRoot(projectPath);
        
        // Verify persistence
        ProjectService projectService2 = new ProjectService(settingsService);
        assertEquals(projectPath.toAbsolutePath().toString(), 
                     projectService2.getCurrentProjectRoot().toAbsolutePath().toString());
        assertEquals("my-project", projectService2.getProjectName());
    }

    @Test
    void testProjectListener() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        
        ProjectService projectService = new ProjectService(settingsService);
        AtomicReference<Path> notifiedPath = new AtomicReference<>();
        
        projectService.addProjectListener(notifiedPath::set);
        
        Path projectPath = tempDir.resolve("new-project");
        projectService.setCurrentProjectRoot(projectPath);
        
        assertEquals(projectPath, notifiedPath.get());
    }
}