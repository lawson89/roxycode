package org.roxycode.app.ai.services.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.service.ProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ProjectPackerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    public void testPackCodebase() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        ProjectCacheMetaService metaService = new ProjectCacheMetaService(projectService);

        // Create some dummy files
        Files.writeString(tempDir.resolve("file1.txt"), "Content 1");

        ProjectPackerService service = new ProjectPackerService(projectService, metaService);
        EditorResult result = service.packCodebase();

        assertTrue(result.success());
        // Note: rg might not find files if it's not a git repo or no files are tracked,
        // but the call itself should succeed.
    }

    @Test
    public void testPackCodebaseWithProgress() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        ProjectCacheMetaService metaService = mock(ProjectCacheMetaService.class);

        ProjectPackerService service = new ProjectPackerService(projectService, metaService);
        ProgressCallback callback = mock(ProgressCallback.class);

        EditorResult result = service.packCodebase(callback);

        assertTrue(result.success());
        // Verify callback was called
        verify(callback, atLeastOnce()).onProgress(anyInt(), anyInt(), anyString());
    }
}
