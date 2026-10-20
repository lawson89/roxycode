package org.roxycode.app.ai.services.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.service.ProjectService;

class RepoMapPackerServiceTest {

    @TempDir Path tempDir;

    private ProjectService projectService;
    private ObjectMapper objectMapper;
    private GrepService grepService;
    private ProjectCacheMetaService metaService;
    private RepoMapPackerService service;

    @BeforeEach
    void setUp() {
        projectService = Mockito.mock(ProjectService.class);
        objectMapper = new ObjectMapper();
        grepService = Mockito.mock(GrepService.class);
        metaService = Mockito.mock(ProjectCacheMetaService.class);
        service = new RepoMapPackerService(projectService, objectMapper, grepService, metaService);
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

    @Test
    void testExcludeBinaryFiles() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        // Create a text file
        Path textFile = tempDir.resolve("test.txt");
        Files.writeString(textFile, "Hello Text");

        // Create a binary file (contains NUL byte)
        Path binaryFile = tempDir.resolve("test.bin");
        Files.write(binaryFile, new byte[] {0, 1, 2, 3});

        when(grepService.listFiles(null)).thenReturn(List.of("test.txt", "test.bin"));

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        assertTrue(result.content().contains("test.txt"));
        assertFalse(result.content().contains("test.bin"), "Binary file should be excluded");
    }
}
