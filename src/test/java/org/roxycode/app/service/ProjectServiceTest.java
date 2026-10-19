package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectServiceTest {

    @TempDir Path tempDir;

    @Test
    void testProjectPersistence() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

        ProjectService projectService = new ProjectService(settingsService);
        Path projectPath = tempDir.resolve("my-project");

        projectService.setCurrentProjectRoot(projectPath);

        // Verify persistence
        ProjectService projectService2 = new ProjectService(settingsService);
        assertEquals(
                projectPath.toAbsolutePath().toString(),
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

    @Test
    void testResolvePathWithinAndOutsideSandbox() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

        ProjectService projectService = new ProjectService(settingsService);
        assertThrows(IllegalStateException.class, () -> projectService.resolvePath("file.txt"));

        Path projectPath = tempDir.resolve("sandbox-project");
        projectService.setCurrentProjectRoot(projectPath);

        assertEquals(projectPath.toAbsolutePath().normalize(), projectService.resolvePath(null));
        assertEquals(
                projectPath.resolve("sub/file.txt").toAbsolutePath().normalize(),
                projectService.resolvePath("sub/file.txt"));
        assertThrows(SecurityException.class, () -> projectService.resolvePath("../escape.txt"));
    }
}
