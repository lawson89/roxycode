package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.events.ProjectChangedEvent;

class ProjectServiceTest {

    @TempDir
    Path tempDir;

    private SettingsService createSettingsService() {
        SettingsService s = new SettingsService(null, null);
        s.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        return s;
    }

    @Test
    void testProjectPersistence() {
        SettingsService settingsService = createSettingsService();
        ProjectService projectService = new ProjectService(settingsService, null);
        Path projectPath = tempDir.resolve("my-project");
        projectService.setCurrentProjectRoot(projectPath);

        ProjectService projectService2 = new ProjectService(settingsService, null);
        assertEquals(projectPath.toAbsolutePath().toString(),
                projectService2.getCurrentProjectRoot().toAbsolutePath().toString());
        assertEquals("my-project", projectService2.getProjectName());
    }

    @Test
    void testProjectListener() {
        AtomicReference<Path> notifiedPath = new AtomicReference<>();
        ProjectService projectService = new ProjectService(createSettingsService(), event -> {
            if (event instanceof ProjectChangedEvent pce) {
                notifiedPath.set(pce.projectRoot());
            }
        });
        Path projectPath = tempDir.resolve("new-project");
        projectService.setCurrentProjectRoot(projectPath);
        assertEquals(projectPath, notifiedPath.get());
    }

    @Test
    void testResolvePathWithinAndOutsideSandbox() {
        ProjectService projectService = new ProjectService(createSettingsService(), null);
        assertThrows(IllegalStateException.class, () -> projectService.resolvePath("file.txt"));

        Path projectPath = tempDir.resolve("sandbox-project");
        projectService.setCurrentProjectRoot(projectPath);

        assertEquals(projectPath.toAbsolutePath().normalize(), projectService.resolvePath(null));
        assertEquals(projectPath.resolve("sub/file.txt").toAbsolutePath().normalize(),
                projectService.resolvePath("sub/file.txt"));
        assertEquals(".", projectService.resolveRelativePath(null));
        assertEquals(".", projectService.resolveRelativePath("   "));
        assertEquals("sub/file.txt", projectService.resolveRelativePath("sub/file.txt"));
        assertThrows(SecurityException.class, () -> projectService.resolvePath("../escape.txt"));
        assertThrows(SecurityException.class, () -> projectService.resolveRelativePath("../escape.txt"));
    }

    @Test
    void testGetProjectDataDirAndResolveProjectDataPath() {
        ProjectService projectService = new ProjectService(createSettingsService(), null);
        assertThrows(IllegalStateException.class, projectService::getProjectDataDir);
        assertThrows(IllegalStateException.class, () -> projectService.resolveProjectDataPath("build.toml"));

        projectService.setCurrentProjectRoot(tempDir.resolve("workspaceA").resolve("My Cool Project!"));
        Path dataDirA = projectService.getProjectDataDir();
        assertEquals(tempDir.resolve("projects"), dataDirA.getParent());
        assertTrue(dataDirA.getFileName().toString().matches("^my-cool-project-[0-9a-f]{8}$"));

        projectService.setCurrentProjectRoot(tempDir.resolve("workspaceB").resolve("My Cool Project!"));
        Path dataDirB = projectService.getProjectDataDir();
        assertNotEquals(dataDirA, dataDirB);
        assertTrue(dataDirB.getFileName().toString().startsWith("my-cool-project-"));

        assertEquals(dataDirB, projectService.resolveProjectDataPath(null));
        assertEquals(dataDirB.resolve("build.toml"), projectService.resolveProjectDataPath("build.toml"));
        assertEquals(dataDirB.resolve("context/rules.md"), projectService.resolveProjectDataPath("context/rules.md"));
        assertThrows(SecurityException.class,
                () -> projectService.resolveProjectDataPath("../other-project/build.toml"));
    }
}
