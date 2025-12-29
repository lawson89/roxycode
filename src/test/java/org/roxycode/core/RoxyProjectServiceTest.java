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

    private Sandbox sandbox;
    private FileSystemService fileSystemService;
    private RoxyProjectService roxyProjectService;

    @BeforeEach
    void setUp() {
        sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        fileSystemService = new FileSystemService(sandbox);
        roxyProjectService = new RoxyProjectService(sandbox, fileSystemService);
    }

    @Test
    void testEnsureProjectStructure_CreatesDirAndReadme() throws IOException {
        Path projectDir = tempDir.resolve("roxy_project");
        Path readmePath = projectDir.resolve("README.md");

        // Initial state: does not exist
        assertFalse(Files.exists(projectDir));
        assertFalse(Files.exists(readmePath));

        // Act
        roxyProjectService.ensureProjectStructure();

        // Assert
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

        // Act
        roxyProjectService.ensureProjectStructure();

        // Assert
        assertTrue(Files.exists(projectDir));
        assertEquals(originalContent, Files.readString(readmePath), "Content should not be overwritten");
    }
}
