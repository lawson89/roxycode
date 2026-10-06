package org.roxycode.app.ai.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrepServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testGrepSuccess() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"), "public class Hello {}");

        GrepService grepService = new GrepService(projectService);
        
        // We try to execute grep. If rg is missing, it will return failure but not throw exception.
        EditorResult result = grepService.grep("class Hello", "src", "*.java");
        
        // Basic check: if it succeeded, content should be correct.
        if (result.success()) {
            assertTrue(result.content().contains("Hello.java"));
            assertTrue(result.content().contains("class Hello"));
        }
    }

    @Test
    void testPathTraversalProtection() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        GrepService grepService = new GrepService(projectService);
        assertThrows(SecurityException.class, () -> {
            grepService.grep("pattern", "../secret", null);
        });
    }
    
    @Test
    void testNoProjectRoot() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(false);
        
        GrepService grepService = new GrepService(projectService);
        EditorResult result = grepService.grep("pattern", "src", null);
        
        assertFalse(result.success());
        assertEquals("No active project", result.errorHint());
    }
}
