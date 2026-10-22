package org.roxycode.app.ai.services.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
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

    @Test
    void testConcurrentInvalidationDuringGenerationDoesNotCacheStaleMap() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Version 1");

        AtomicBoolean invalidateDuringList = new AtomicBoolean(true);
        when(grepService.listFiles(null))
                .thenAnswer(
                        invocation -> {
                            if (invalidateDuringList.compareAndSet(true, false)) {
                                // Simulate a concurrent FileChangedEvent while generateRepoMap() is
                                // mid-execution
                                service.handleFileChanged(new FileChangedEvent("test.txt"));
                            }
                            return List.of("test.txt");
                        });

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertTrue(firstResult.content().contains("Version 1"));
        // Stale generation must not be persisted to metaService
        verify(metaService, never()).saveRepoCache(Mockito.anyString());

        // Update file content; next call must regenerate instead of returning stale cache
        Files.writeString(testFile, "Version 2");
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("Version 2"));
        verify(grepService, times(2)).listFiles(null);
        verify(metaService, times(1)).saveRepoCache(secondResult.content());

        // Third call without invalidation should use the cached map
        EditorResult thirdResult = service.generateRepoMap();
        assertTrue(thirdResult.success());
        assertEquals(secondResult.content(), thirdResult.content());
        verify(grepService, times(2)).listFiles(null);
    }
}
