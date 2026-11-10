package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ThinkingLevel;

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

        assertFalse(projectService.isBuildConfirmed());
        projectService.setBuildConfirmed(true);
        assertTrue(projectService.isBuildConfirmed());
        projectService.setProjectGeminiModel("gemini-3.5-pro");
        assertTrue(projectService.isBuildConfirmed());
        assertEquals("gemini-3.5-pro", projectService.getProjectGeminiModel());

        settingsService.getSettings().setThinkingLevel("MEDIUM");
        assertEquals("", projectService.getProjectThinkingLevel());
        assertEquals(ThinkingLevel.MEDIUM, projectService.getEffectiveThinkingLevel());

        publishedEvent.set(null);
        projectService.setProjectThinkingLevel("  high  ");
        assertEquals("HIGH", projectService.getProjectThinkingLevel());
        assertEquals(ThinkingLevel.HIGH, projectService.getEffectiveThinkingLevel());
        assertEquals("gemini-3.5-pro", projectService.getProjectGeminiModel());
        assertTrue(projectService.isBuildConfirmed());
        assertInstanceOf(org.roxycode.app.events.SettingsChangedEvent.class, publishedEvent.get());

        projectService.setProjectThinkingLevel("   ");
        assertEquals("", projectService.getProjectThinkingLevel());
        assertEquals(ThinkingLevel.MEDIUM, projectService.getEffectiveThinkingLevel());

        Path legacyProjectConfig = projectService.resolveProjectDataPath(ProjectService.PROJECT_CONFIG_FILE);
        assertDoesNotThrow(() -> Files.writeString(legacyProjectConfig, """
                geminiModel = "gemini-legacy"
                buildConfirmed = true
                """));
        assertEquals("gemini-legacy", projectService.getProjectGeminiModel());
        assertEquals("", projectService.getProjectThinkingLevel());
        assertTrue(projectService.isBuildConfirmed());
    }

    @Test
    void testReferenceFolderMountsCollisionDisambiguationAndReadWriteSandboxing() {
        SettingsService settingsService = createSettingsService();
        ProjectService projectService = new ProjectService(settingsService, null);
        Path projectRoot = tempDir.resolve("active-project");
        projectService.setCurrentProjectRoot(projectRoot);

        Path projBilling = tempDir.resolve("proj-repos").resolve("Billing Service!");
        Path globalBilling = tempDir.resolve("global-repos").resolve("billing-service");
        Path globalShared = tempDir.resolve("global-repos").resolve("shared-commons");

        projectService.setProjectReferenceFolders(List.of(projBilling.toString(), "rel-ref"));
        projectService.setGlobalReferenceFolders(List.of(globalBilling.toString(), globalShared.toString()));

        List<ProjectService.ReferenceFolderMount> mounts = projectService.getReferenceFolderMounts();
        assertEquals(4, mounts.size());

        assertEquals("billing-service", mounts.get(0).alias());
        assertEquals("@billing-service", mounts.get(0).aliasPrefix());
        assertEquals(projBilling.toAbsolutePath().normalize(), mounts.get(0).rootPath());
        assertFalse(mounts.get(0).globalScope());

        assertEquals("rel-ref", mounts.get(1).alias());
        assertEquals(projectRoot.resolve("rel-ref").toAbsolutePath().normalize(), mounts.get(1).rootPath());
        assertFalse(mounts.get(1).globalScope());

        assertEquals("billing-service-2", mounts.get(2).alias());
        assertEquals("@billing-service-2", mounts.get(2).aliasPrefix());
        assertEquals(globalBilling.toAbsolutePath().normalize(), mounts.get(2).rootPath());
        assertTrue(mounts.get(2).globalScope());

        assertEquals("shared-commons", mounts.get(3).alias());
        assertEquals(globalShared.toAbsolutePath().normalize(), mounts.get(3).rootPath());
        assertTrue(mounts.get(3).globalScope());

        SecurityException writeEx = assertThrows(SecurityException.class,
                () -> projectService.resolvePath("@billing-service/src/Invoice.java"));
        assertTrue(writeEx.getMessage().contains("Reference folder path is strictly read-only:"));
        assertThrows(SecurityException.class, () -> projectService.resolvePath("./@billing-service"));

        assertEquals(projBilling.toAbsolutePath().normalize(), projectService.resolveReadPath("@billing-service"));
        Path resolvedInvoice = projectService.resolveReadPath("@billing-service/src/main/Invoice.java");
        assertEquals(projBilling.resolve("src/main/Invoice.java").toAbsolutePath().normalize(), resolvedInvoice);
        assertEquals("@billing-service/src/main/Invoice.java",
                projectService.formatReadDisplayPath(resolvedInvoice, "fallback"));

        assertEquals(globalBilling.resolve("api/Client.java").toAbsolutePath().normalize(),
                projectService.resolveReadPath("@billing-service-2/api/Client.java"));

        assertTrue(ProjectService.isReferencePath("@billing-service/../pom.xml"));
        assertTrue(ProjectService.isReferencePath("@billing-service/.."));
        assertThrows(SecurityException.class, () -> projectService.resolvePath("@billing-service/../pom.xml"));
        assertThrows(SecurityException.class, () -> projectService.resolveReadPath("@billing-service/../pom.xml"));
        assertThrows(SecurityException.class, () -> projectService.resolveReadPath("@billing-service/.."));
        assertThrows(SecurityException.class,
                () -> projectService.resolveReadPath("@billing-service/../../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> projectService.resolveReadPath("@unknown-alias/src/App.java"));
        assertThrows(IllegalArgumentException.class, () -> projectService.resolveReadPath("@../pom.xml"));

        ProjectService.ResolvedSearchTarget rootTarget = projectService.resolveSearchTarget("@billing-service");
        assertEquals(projBilling.toAbsolutePath().normalize(), rootTarget.workingDir());
        assertEquals(".", rootTarget.relativeTarget());
        assertEquals("@billing-service", rootTarget.prefixAlias());
        assertTrue(rootTarget.isReferenceMount());
        assertEquals("@billing-service/src/Invoice.java", rootTarget.formatResultPath("./src/Invoice.java"));
        String bs = String.valueOf((char) 92);
        String snippet = "String s = " + bs + "s+ https://a.com/../b;";
        assertEquals("@billing-service/src/Invoice.java:10:1:" + snippet,
                rootTarget.formatMatchLine("./src" + bs + "Invoice.java:10:1:" + snippet));
        assertEquals("src/Invoice.java-11-    // https://a.com/../b " + bs + "n",
                new ProjectService.ResolvedSearchTarget(tempDir, ".", "")
                        .formatMatchLine("./src" + bs + "Invoice.java-11-    // https://a.com/../b " + bs + "n"));

        ProjectService.ResolvedSearchTarget subTarget = projectService.resolveSearchTarget("@billing-service/src/main");
        assertEquals(projBilling.toAbsolutePath().normalize(), subTarget.workingDir());
        assertEquals("src/main", subTarget.relativeTarget());
        assertThrows(SecurityException.class, () -> projectService.resolveSearchTarget("@billing-service/.."));
        assertThrows(SecurityException.class,
                () -> projectService.resolveSearchTarget("@billing-service/../../outside"));
    }
}
