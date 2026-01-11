package org.roxycode.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.tools.service.FileSystemService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RoxyProjectServiceTest {

    @TempDir
    Path tempDir;

    private RoxyProjectService roxyProjectService;

    @BeforeEach
    void setUp() {
        Sandbox sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        FileSystemService fileSystemService = new FileSystemService(sandbox);
        roxyProjectService = new RoxyProjectService(sandbox, fileSystemService, null, null, null);
    }

    @Test
    void testEnsureProjectStructure_CreatesDirAndReadme() throws IOException {
        Path projectDir = tempDir.resolve(RoxyProjectService.ROXY_WORKING_DIR);
        Path readmePath = projectDir.resolve("README.md");

        assertFalse(Files.exists(projectDir));
        assertFalse(Files.exists(readmePath));

        roxyProjectService.ensureProjectStructure();

        assertTrue(Files.exists(projectDir), "Directory should be created");
        assertTrue(Files.exists(readmePath), "README.md should be created");
        
        String content = Files.readString(readmePath);
        assertTrue(content.contains("# Roxy Project"));
    }

    @Test
    void testEnsureProjectStructure_PreservesExistingContent() throws IOException {
        Path projectDir = tempDir.resolve("roxy_project");
        Files.createDirectories(projectDir);
        Path readmePath = projectDir.resolve("README.md");
        String originalContent = "Original Content";
        Files.writeString(readmePath, originalContent);

        roxyProjectService.ensureProjectStructure();

        assertTrue(Files.exists(projectDir));
        assertEquals(originalContent, Files.readString(readmePath), "Content should not be overwritten");
    }
    @Test
    void testSetCurrentPlan_ResetsToPlanModeWhenPlanCleared() {
        roxyProjectService.setCurrentPlan("my-plan");
        roxyProjectService.setCurrentMode(RoxyMode.CODE);
        assertEquals(RoxyMode.CODE, roxyProjectService.getCurrentMode());

        roxyProjectService.setCurrentPlan(null);
        assertEquals(RoxyMode.PLAN, roxyProjectService.getCurrentMode());
    }


}
