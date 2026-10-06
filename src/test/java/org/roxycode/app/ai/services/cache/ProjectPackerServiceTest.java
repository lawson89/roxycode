package org.roxycode.app.ai.services.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.ProjectService;
import java.util.List;
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
        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles(null)).thenReturn(List.of("file1.txt"));

        PromptService promptService = mock(PromptService.class);
        when(promptService.loadCoreWorkflowPrompt()).thenReturn("Mocked Instructions");
        when(promptService.loadJexlContext()).thenReturn("Mocked JEXL Context");

        JexlServiceRegistry registry = mock(JexlServiceRegistry.class);
        when(registry.getDocumentation()).thenReturn("Mocked API Docs");

        // Create some dummy files
        Files.writeString(tempDir.resolve("file1.txt"), "Content 1");

        ProjectPackerService service = new ProjectPackerService(projectService, metaService, grepService, promptService, registry);
        EditorResult result = service.packCodebase();

        assertTrue(result.success());
        String packed = result.content();
        assertTrue(packed.contains("## Agent Instructions"));
        assertTrue(packed.contains("Mocked Instructions"));
        assertTrue(packed.contains("Mocked JEXL Context"));
        assertTrue(packed.contains("## JEXL API Documentation"));
        assertTrue(packed.contains("Mocked API Docs"));
    }

    @Test
    public void testPackCodebaseWithProgress() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        ProjectCacheMetaService metaService = mock(ProjectCacheMetaService.class);
        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles(null)).thenReturn(List.of("file1.txt"));

        PromptService promptService = mock(PromptService.class);
        JexlServiceRegistry registry = mock(JexlServiceRegistry.class);

        ProjectPackerService service = new ProjectPackerService(projectService, metaService, grepService, promptService, registry);
        ProgressCallback callback = mock(ProgressCallback.class);

        EditorResult result = service.packCodebase(callback);

        assertTrue(result.success());
        // Verify callback was called
        verify(callback, atLeastOnce()).onProgress(anyInt(), anyInt(), anyString());
    }
}
