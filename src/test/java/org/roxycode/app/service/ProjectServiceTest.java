package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.events.ProjectChangedEvent;

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

        AtomicReference<Path> notifiedPath = new AtomicReference<>();
        ProjectService projectService =
                new ProjectService(
                        settingsService,
                        event -> {
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

    @Test
    void testGetProjectDataDirAndResolveProjectDataPath() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));

        ProjectService projectService = new ProjectService(settingsService);
        assertThrows(IllegalStateException.class, projectService::getProjectDataDir);
        assertThrows(
                IllegalStateException.class,
                () -> projectService.resolveProjectDataPath("build.toml"));

        Path projectA = tempDir.resolve("workspaceA").resolve("My Cool Project!");
        projectService.setCurrentProjectRoot(projectA);

        Path dataDirA = projectService.getProjectDataDir();
        assertEquals(tempDir.resolve("projects"), dataDirA.getParent());
        String dirNameA = dataDirA.getFileName().toString();
        assertTrue(
                dirNameA.matches("^my-cool-project-[0-9a-f]{8}$"),
                "Expected sanitized name + 8-char hex hash, got: " + dirNameA);

        // Same folder name in a different parent directory produces a distinct hash
        Path projectB = tempDir.resolve("workspaceB").resolve("My Cool Project!");
        projectService.setCurrentProjectRoot(projectB);
        Path dataDirB = projectService.getProjectDataDir();
        assertNotEquals(dataDirA, dataDirB);
        assertTrue(
                dataDirB.getFileName().toString().startsWith("my-cool-project-"),
                "Both should share sanitized prefix");

        // Sandbox resolution within project data dir
        assertEquals(dataDirB, projectService.resolveProjectDataPath(null));
        assertEquals(
                dataDirB.resolve("build.toml"),
                projectService.resolveProjectDataPath("build.toml"));
        assertEquals(
                dataDirB.resolve("context/rules.md"),
                projectService.resolveProjectDataPath("context/rules.md"));
        assertThrows(
                SecurityException.class,
                () -> projectService.resolveProjectDataPath("../other-project/build.toml"));
    }

    @Test
    void testNotAnnotatedWithAgentServiceOrAgentDoc() {
        assertNull(
                ProjectService.class.getAnnotation(AgentService.class),
                "ProjectService must not be exposed as an @AgentService");
        assertNull(
                ProjectService.class.getAnnotation(AgentDoc.class),
                "ProjectService class must not have @AgentDoc");

        for (Method method : ProjectService.class.getDeclaredMethods()) {
            assertNull(
                    method.getAnnotation(AgentDoc.class),
                    "Method " + method.getName() + " must not have @AgentDoc");
        }
    }
}
