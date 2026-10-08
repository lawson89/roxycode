package org.roxycode.app.ai.services.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.model.ProjectCacheMeta;
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
        ProjectCacheMeta meta = new ProjectCacheMeta("TestProject", now, 5000);

        service.saveMeta(meta);

        Optional<ProjectCacheMeta> loaded = service.loadMeta();
        assertTrue(loaded.isPresent());
        assertEquals("TestProject", loaded.get().projectName());
        assertEquals(5000, loaded.get().estimatedTokens());

        // Check that .roxycode/tmp directory was created
        assertTrue(Files.exists(tempDir.resolve(".roxycode/tmp")));
        assertTrue(Files.exists(tempDir.resolve(".roxycode/tmp/cache_meta.toml")));
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
        assertTrue(Files.exists(tempDir.resolve(".roxycode/tmp/repo_cache.txt")));
    }

    @Test
    public void testMigrateLegacyFiles() throws IOException {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        Path roxyDir = tempDir.resolve(".roxycode");
        Files.createDirectories(roxyDir);
        String legacyToml = "projectName = 'LegacyProject'\nestimatedTokens = 1234\nlastUpdated = '2026-01-01T00:00:00'\n";
        Files.writeString(roxyDir.resolve("cache_meta.toml"), legacyToml);
        Files.writeString(roxyDir.resolve("repo_cache.txt"), "Legacy repo content");

        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);

        Optional<ProjectCacheMeta> loadedMeta = service.loadMeta();
        assertTrue(loadedMeta.isPresent());
        assertEquals("LegacyProject", loadedMeta.get().projectName());
        assertEquals(1234, loadedMeta.get().estimatedTokens());

        Optional<String> loadedRepo = service.loadRepoCache();
        assertTrue(loadedRepo.isPresent());
        assertEquals("Legacy repo content", loadedRepo.get());

        // Ensure legacy files moved to .roxycode/tmp
        assertTrue(Files.exists(tempDir.resolve(".roxycode/tmp/cache_meta.toml")));
        assertTrue(Files.exists(tempDir.resolve(".roxycode/tmp/repo_cache.txt")));
        assertFalse(Files.exists(roxyDir.resolve("cache_meta.toml")));
        assertFalse(Files.exists(roxyDir.resolve("repo_cache.txt")));
    }
}
