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
        AtomicReference<Path> notifiedPath = new AtomicReference<>();
        ProjectService projectService = new ProjectService(settingsService, event -> {
            if (event instanceof ProjectChangedEvent pce) {
                notifiedPath.set(pce.projectRoot());
            }
        });
        Path projectPath = tempDir.resolve("my-project");
        projectService.setCurrentProjectRoot(projectPath);
        assertEquals(projectPath, notifiedPath.get());

        ProjectService projectService2 = new ProjectService(settingsService, null);
        assertEquals(projectPath.toAbsolutePath().toString(),
                projectService2.getCurrentProjectRoot().toAbsolutePath().toString());
        assertEquals("my-project", projectService2.getProjectName());
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
        assertEquals(projectService.resolveProjectDataPath("tmp/build.log"),
                projectService.resolvePath(".roxycode/tmp/build.log"));
        assertEquals(projectService.resolveProjectDataPath("tmp/build.log"),
                projectService.resolvePath("./.roxycode/tmp/build.log"));
        assertEquals(projectService.resolveProjectDataPath("tmp"), projectService.resolvePath(".roxycode/tmp"));
        assertThrows(SecurityException.class, () -> projectService.resolvePath(".roxycode/tmp/../project.toml"));
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

    @Test
    void testProjectGeminiModelOverrideAndEffectiveModelResolution() {
        SettingsService settingsService = createSettingsService();
        settingsService.getSettings().setGeminiModel("gemini-global-default");
        AtomicReference<Object> publishedEvent = new AtomicReference<>();
        ProjectService projectService = new ProjectService(settingsService, publishedEvent::set);

        assertEquals("", projectService.getProjectGeminiModel());
        assertEquals("gemini-global-default", projectService.getEffectiveGeminiModel());
        assertThrows(IllegalStateException.class, () -> projectService.setProjectGeminiModel("gemini-project-custom"));

        Path projectA = tempDir.resolve("project-a");
        projectService.setCurrentProjectRoot(projectA);
        assertEquals("", projectService.getProjectGeminiModel());
        assertEquals("gemini-global-default", projectService.getEffectiveGeminiModel());

        publishedEvent.set(null);
        projectService.setProjectGeminiModel("  gemini-project-custom  ");
        assertEquals("gemini-project-custom", projectService.getProjectGeminiModel());
        assertEquals("gemini-project-custom", projectService.getEffectiveGeminiModel());
        assertInstanceOf(org.roxycode.app.events.SettingsChangedEvent.class, publishedEvent.get());

        Path projectB = tempDir.resolve("project-b");
        projectService.setCurrentProjectRoot(projectB);
        assertEquals("", projectService.getProjectGeminiModel());
        assertEquals("gemini-global-default", projectService.getEffectiveGeminiModel());

        projectService.setCurrentProjectRoot(projectA);
        assertEquals("gemini-project-custom", projectService.getProjectGeminiModel());
        assertEquals("gemini-project-custom", projectService.getEffectiveGeminiModel());

        projectService.setProjectGeminiModel("   ");
        assertEquals("", projectService.getProjectGeminiModel());
        assertEquals("gemini-global-default", projectService.getEffectiveGeminiModel());
    }
}
