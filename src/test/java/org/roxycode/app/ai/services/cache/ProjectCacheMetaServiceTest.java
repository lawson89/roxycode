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
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.TokenUtils;

public class ProjectCacheMetaServiceTest {

    @TempDir Path tempDir;

    private Path projectRoot;
    private Path projectDataDir;
    private ProjectService projectService;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("workspace");
        projectDataDir = tempDir.resolve("roxy-home/projects/workspace-12345678");
        Files.createDirectories(projectRoot);

        projectService = mock(ProjectService.class);
        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(projectRoot);
        lenient().when(projectService.getProjectName()).thenReturn("TestProject");
        lenient()
                .when(projectService.resolveProjectDataPath(anyString()))
                .thenAnswer(inv -> projectDataDir.resolve(inv.getArgument(0, String.class)));
    }

    @Test
    public void testSaveRepoCacheAndDeriveMetaWithoutTomlFile() throws IOException {
        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);

        String content = "Packed repository map content for testing token estimation";
        service.saveRepoCache(content);

        Optional<String> loadedRepo = service.loadRepoCache();
        assertTrue(loadedRepo.isPresent());
        assertEquals(content, loadedRepo.get());

        Optional<ProjectCacheMeta> loadedMeta = service.loadMeta();
        assertTrue(loadedMeta.isPresent());
        assertEquals("TestProject", loadedMeta.get().projectName());
        assertEquals(TokenUtils.estimateTokens(content), loadedMeta.get().estimatedTokens());
        assertNotNull(loadedMeta.get().lastUpdated());

        // Verify only tmp/repo_cache.txt exists under projectDataDir and no cache_meta.toml
        assertTrue(Files.exists(projectDataDir.resolve("tmp/repo_cache.txt")));
        assertFalse(Files.exists(projectDataDir.resolve("tmp/cache_meta.toml")));
        assertFalse(Files.exists(projectRoot.resolve(".roxycode")));
    }

    @Test
    public void testInMemoryCacheAndInvalidationOnProjectChange() throws IOException {
        ProjectCacheMetaService service = new ProjectCacheMetaService(projectService);

        // First load when no file exists caches empty result
        assertTrue(service.loadMeta().isEmpty());
        assertTrue(service.loadMeta().isEmpty());
        verify(projectService, times(1)).resolveProjectDataPath("tmp/repo_cache.txt");

        // saveRepoCache populates in-memory meta cache so subsequent loadMeta does not hit disk
        String content = "Cached repository map content";
        service.saveRepoCache(content);
        clearInvocations(projectService);

        Optional<ProjectCacheMeta> cached1 = service.loadMeta();
        Optional<ProjectCacheMeta> cached2 = service.loadMeta();
        assertTrue(cached1.isPresent());
        assertEquals("TestProject", cached1.get().projectName());
        assertSame(cached1.get(), cached2.get());
        verify(projectService, never()).resolveProjectDataPath("tmp/repo_cache.txt");

        // ProjectChangedEvent invalidates the in-memory cache so next loadMeta derives from disk
        service.onProjectChanged(new ProjectChangedEvent(projectRoot));
        Optional<ProjectCacheMeta> reloaded = service.loadMeta();
        assertTrue(reloaded.isPresent());
        assertEquals("TestProject", reloaded.get().projectName());
        assertEquals(TokenUtils.estimateTokens(content), reloaded.get().estimatedTokens());
        verify(projectService, times(1)).resolveProjectDataPath("tmp/repo_cache.txt");

        // Subsequent loadMeta uses in-memory cache again
        service.loadMeta();
        verify(projectService, times(1)).resolveProjectDataPath("tmp/repo_cache.txt");
    }

    @Test
    public void testDeleteRepoCacheRemovesDiskFileAndClearsInMemoryCache() throws IOException {
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
