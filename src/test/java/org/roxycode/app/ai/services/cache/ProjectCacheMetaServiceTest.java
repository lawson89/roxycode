package org.roxycode.app.ai.services.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.model.cache.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ProjectCacheMetaServiceTest {

    @TempDir
    Path tempDir;

    @Test
    public void testSaveAndLoadMeta() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);
        
        LocalDateTime now = LocalDateTime.now();
        ProjectCacheMeta meta = new ProjectCacheMeta("TestProject", now, "cache-123", 3600, 5000);
        
        service.saveMeta(meta);
        
        Optional<ProjectCacheMeta> loaded = service.loadMeta();
        assertTrue(loaded.isPresent());
        assertEquals("TestProject", loaded.get().projectName());
        assertEquals("cache-123", loaded.get().cacheName());
        assertEquals(5000, loaded.get().estimatedTokens());
        
        // Check that .roxycode directory was created
        assertTrue(Files.exists(tempDir.resolve(".roxycode")));
        assertTrue(Files.exists(tempDir.resolve(".roxycode/cache_meta.toml")));
    }
    
    @Test
    public void testRepoCache() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);
        String content = "Packed content";
        
        service.saveRepoCache(content);
        
        Optional<String> loaded = service.loadRepoCache();
        assertTrue(loaded.isPresent());
        assertEquals(content, loaded.get());
        assertTrue(Files.exists(tempDir.resolve(".roxycode/repo_cache.txt")));
    }
}
