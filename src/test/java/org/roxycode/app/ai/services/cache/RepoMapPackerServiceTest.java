package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.service.ProjectService;
import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class RepoMapPackerServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private ObjectMapper objectMapper;
    private GrepService grepService;
    private RepoMapPackerService service;

    @BeforeEach
    void setUp() {
        projectService = Mockito.mock(ProjectService.class);
        objectMapper = new ObjectMapper();
        grepService = Mockito.mock(GrepService.class);
        service = new RepoMapPackerService(projectService, objectMapper, grepService);
    }

    @Test
    void testNoActiveProject() {
        when(projectService.hasActiveProject()).thenReturn(false);
        EditorResult result = service.generateRepoMap();
        assertFalse(result.success());
        assertEquals("No active project", result.errorHint());
    }

    @Test
    void testGenerateRepoMapWithSmallFile() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello World");
        when(grepService.listFiles(null)).thenReturn(List.of("test.txt"));

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        assertTrue(result.content().contains("# Repo Map Snapshot"));
        assertTrue(result.content().contains("## Project Tree"));
    }
}
