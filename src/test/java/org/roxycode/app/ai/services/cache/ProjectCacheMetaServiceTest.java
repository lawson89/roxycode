package org.roxycode.app.ai.services.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.TokenUtils;

public class ProjectCacheMetaServiceTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path projectDataDir;
    private ProjectService projectService;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = Files.createDirectories(tempDir.resolve("workspace"));
        projectDataDir = tempDir.resolve("roxy-home/projects/workspace-12345678");
        projectService = mock(ProjectService.class);
        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        lenient().when(projectService.getProjectName()).thenReturn("TestProject");
        lenient().when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
    }

    @Test
    public void testSaveRepoCacheAndDeriveMetaWithoutTomlFile() {
        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);
        String content = "Packed repository map content for testing token estimation";
        service.saveRepoCache(content);

        assertEquals(Optional.of(content), service.loadRepoCache());
        Optional<ProjectCacheMeta> loadedMeta = service.loadMeta();
        assertTrue(loadedMeta.isPresent());
        assertEquals("TestProject", loadedMeta.get().projectName());
        assertEquals(TokenUtils.estimateTokens(content), loadedMeta.get().estimatedTokens());
        assertNotNull(loadedMeta.get().lastUpdated());
        assertTrue(Files.exists(projectDataDir.resolve("tmp/repo_cache.txt")));
        assertFalse(Files.exists(projectDataDir.resolve("tmp/cache_meta.toml")));
        assertFalse(Files.exists(projectRoot.resolve(".roxycode")));
    }

    @Test
    public void testStatelessLoadMetaReflectsActiveProjectAndDiskChanges() throws IOException {
        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);
        assertTrue(service.loadMeta().isEmpty());

        String content = "Cached repository map content";
        service.saveRepoCache(content);

        Optional<ProjectCacheMeta> loaded = service.loadMeta();
        assertTrue(loaded.isPresent());
        assertEquals("TestProject", loaded.get().projectName());
        assertEquals(TokenUtils.estimateTokens(content), loaded.get().estimatedTokens());

        String updatedContent = "Updated repository map content with more tokens for testing";
        Files.writeString(projectDataDir.resolve("tmp/repo_cache.txt"), updatedContent);
        Optional<ProjectCacheMeta> reloaded = service.loadMeta();
        assertTrue(reloaded.isPresent());
        assertEquals(TokenUtils.estimateTokens(updatedContent), reloaded.get().estimatedTokens());

        when(projectService.hasActiveProject()).thenReturn(false);
        assertTrue(service.loadMeta().isEmpty());
        assertTrue(service.loadRepoCache().isEmpty());
    }

    @Test
    public void testDeleteRepoCacheRemovesDiskFile() {
        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);
        service.saveRepoCache("Cached content to be deleted");
        Path cacheFile = projectDataDir.resolve("tmp/repo_cache.txt");
        assertTrue(Files.exists(cacheFile));
        assertTrue(service.loadMeta().isPresent());
        assertTrue(service.loadRepoCache().isPresent());

        service.deleteRepoCache();
        assertFalse(Files.exists(cacheFile));
        assertTrue(service.loadRepoCache().isEmpty());
        assertTrue(service.loadMeta().isEmpty());
    }
}
